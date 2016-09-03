package demesne

import scala.concurrent.{ExecutionContext, Future}
import akka.Done
import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, Terminated}
import akka.agent.Agent
import akka.util.Timeout
import scalaz.concurrent.Task
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import demesne.repository.{RepositorySupervisor, StartProtocol}
import demesne.DomainModel.{AggregateIndex, DomainModelCell}
import demesne.index.{IndexBus, IndexSupervisor}
import peds.commons.TryV
import peds.commons.log.Trace


/**
  * Created by rolfsd on 8/26/16.
  */
abstract class BoundedContext {
  def name: String
  def system: ActorSystem
  def model: DomainModel
  def futureModel: Future[DomainModel]

  def :+( rootType: AggregateRootType ): BoundedContext
  def +:( rootType: AggregateRootType ): BoundedContext = this :+ rootType
  def withResources( resources: Map[Symbol, Any] ): BoundedContext
  def withStartTask( task: Task[Done] ): BoundedContext
  def start(): Future[DomainModel]
  def shutdown(): Future[Terminated] = system.terminate()
}

object BoundedContext extends LazyLogging {
  private val trace = Trace[BoundedContext.type]

  case class Key( name: String, system: ActorSystem )

  def apply( name: String, configuration: Config = ConfigFactory.empty() )( implicit system: ActorSystem ): BoundedContext = {
    val key = Key( name, system )
    val model = DomainModelCell( name, system, new IndexBus )
    modelRegistry send { models => models + (key -> model) }
    BoundedContextImpl( name, system, configuration )
  }

  import scala.concurrent.ExecutionContext.global
  private val modelRegistry: Agent[Map[Key, DomainModelCell]] = Agent( Map.empty[Key, DomainModelCell] )( global )


  final case class BoundedContextImpl private[BoundedContext](
    override val name: String,
    override val system: ActorSystem,
    configuration: Config,
    userResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    startTasks: Seq[Task[Done]] = Seq.empty[Task[Done]]
  ) extends BoundedContext {
    override def toString: String = {
      s"""BoundedContext(${name}, system:[${system}], model:[${modelRegistry.get().getOrElse(key, "no-model")}]"""
    }

    val key = Key( name, system )

    override def model: DomainModel = DomainModelRef( name, system )

    override def futureModel: Future[DomainModel] = {
      implicit val ec = system.dispatcher
      modelRegistry.future() map { _ => model }
    }

    override def :+( rootType: AggregateRootType ): BoundedContext = trace.block(s":+ [${rootType.name}]") {
      implicit val ec = system.dispatcher
      updateModelCell { cell =>
        logger.debug( "in updateModelCell - adding rootType:[{}] to domain-model-cell:[{}]", rootType.name, cell )
        cell addAggregateType rootType
      }
      this
    }

    override def withResources( resources: Map[Symbol, Any] ): BoundedContext = {
      this.copy( userResources = userResources ++ resources )
    }

    override def withStartTask( task: Task[Done] ): BoundedContext = this.copy( startTasks = startTasks :+ task )

    private def updateModelCell(
      fn: DomainModelCell => DomainModelCell
    )(
      implicit ec: ExecutionContext
    ): Future[DomainModelCell] = trace.block("updateModelCell") {
      modelRegistry
      .alter { models =>
        logger.debug( "LOOKING for model cell:[{}]", key )
        models.get( key )
        .map { m =>
          logger.debug( "ALTERING - key:[{}] found:[{}]", key, m )
          models + ( key -> fn(m) )
        }
        .getOrElse { models }
      }
      .map { models => models( key ) }
    }

    override def start(): Future[DomainModel] = {
      import scala.concurrent.duration._
      import akka.pattern.AskableActorSelection

      logger.debug( "starting BoundedContext:[{}]...", (name, system.name) )
      implicit val ec = system.dispatcher
      implicit val timeout = Timeout( 3.seconds )
      val repositorySupervisorName = name + "-repositories"
      val sel = system.actorSelection( "/user/"+repositorySupervisorName )
      val asker = new AskableActorSelection( sel )
      val result = DomainModelRef( name, system )

      def verifySupervisor(): Future[Done] = {
        ( asker ? new Identify( repositorySupervisorName.## ) ).mapTo[ActorIdentity] flatMap { identity =>
          identity.ref
          .map { _ =>
            logger.debug( "BC: repository supervisor:[{}] found created" )
            Future successful Done
          }
          .getOrElse { setupSupervisors() }
        }
      }

      def setupSupervisors(): Future[Done] = {
        for {
          cell <- modelCell()
          _ = logger.debug( "BC: making repo supervisor" )
          indexSupervisor <- makeIndexSupervisor( cell )
          repoSupervisor <- makeRepositorySupervisor( repositorySupervisorName, cell.rootTypes, userResources )
          _ = logger.debug( "BC: updating model cell" )
          newCell <- updateModelCell {
            _.copy( indexSupervisor = Some(indexSupervisor), repositorySupervisor = Some(repoSupervisor) )
          }
          _ = logger.debug( "BC: starting model cell = [{}]", newCell )
          _ <- newCell.start()
          _ = logger.debug( "BC: model cell started" )
        } yield {
          Done
        }
      }

      import peds.commons.concurrent.TaskExtensionOps

      val tasks = new TaskExtensionOps( Task.gatherUnordered( startTasks ) )

      for {
        _ <- tasks.runFuture()
        _ <- verifySupervisor()
      } yield {
        logger.info( "started BoundedContext:[{}]", (name, system.name) )
        result
      }
    }

    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout) = {
      import scala.concurrent.duration._
      val baseline = to.duration - 100.millis
      ( Timeout( baseline / 2 ), Timeout( baseline / 2 ) )
    }

    private def modelCell()( implicit ec: ExecutionContext ): Future[DomainModelCell] = {
      modelRegistry.future() map {
        _.get( key ) getOrElse { throw new DomainModelNotRegisteredError(name, system) }
      }
    }

    private def makeRepositorySupervisor(
      repositorySupervisorName: String,
      rootTypes: Set[AggregateRootType],
      userResources: Map[Symbol, Any]
    )(
      implicit to: Timeout
    ): Future[ActorRef] = {
      import akka.pattern.ask
      implicit val ec = system.dispatcher
      val props = RepositorySupervisor.props( model, rootTypes, userResources, configuration )
      val supervisor = system.actorOf( props, repositorySupervisorName )
      ( supervisor ? StartProtocol.WaitForStart ) map { _ =>
        logger.debug(
          "started repository supervisor:[{}] for root-types:[{}]",
          repositorySupervisorName,
          rootTypes.map{ _.name }.mkString( ", " )
        )

        supervisor
      }
    }

    private def makeIndexSupervisor( model: DomainModel ): Future[ActorRef] = {
      Future successful { system.actorOf( IndexSupervisor.props( model.indexBus ), s"${model.name}-indexes" ) }
    }
  }


  final case class DomainModelRef private[BoundedContext](
    override val name: String,
    override val system: ActorSystem
  ) extends DomainModel {
    val key = Key( name, system )

    private def underlying: DomainModel = modelRegistry()( key )
    override def indexBus: IndexBus = underlying.indexBus

    override def get( rootName: String, id: Any ): Option[ActorRef] = underlying.get( rootName, id )

    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
      underlying.aggregateIndexFor[K, TID, V]( rootName, indexName )
    }

    override def rootTypes: Set[AggregateRootType] = underlying.rootTypes
  }


  final case class DomainModelNotRegisteredError private[demesne]( name: String, system: ActorSystem )
    extends NoSuchElementException( s"no DomainModel registered for name [$name] and system [$system]" )
    with DemesneError
}