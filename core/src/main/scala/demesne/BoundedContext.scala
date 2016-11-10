package demesne

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.Done
import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, Terminated}
import akka.agent.Agent
import akka.pattern.{AskableActorSelection, ask}
import akka.util.Timeout

import scalaz.concurrent.Task
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import peds.commons.TryV
import peds.commons.log.Trace
import demesne.repository.{RepositorySupervisor, StartProtocol}
import demesne.DomainModel.{AggregateIndex, DomainModelCell, Supervisors}
import demesne.index.{IndexBus, IndexSupervisor}


/**
  * Created by rolfsd on 8/26/16.
  */
abstract class BoundedContext {
  def name: String
  def system: ActorSystem
  def unsafeModel: DomainModel
  def futureModel: Future[DomainModel]
  def resources: Map[Symbol, Any]
  def configuration: Config
  def :+( rootType: AggregateRootType ): BoundedContext
  def +:( rootType: AggregateRootType ): BoundedContext = this :+ rootType
  def withResources( rs: Map[Symbol, Any] ): BoundedContext
  def withStartTask( task: StartTask ): BoundedContext
  def withStartTask( description: String )( task: Task[Done] ): BoundedContext = {
    withStartTask( StartTask.withUnitTask( description )( task ) )
  }
  def withStartFunction( description: String )( task: BoundedContext => Done ): BoundedContext = {
    withStartTask( StartTask.withFunction( description )( task ) )
  }
  def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext]
  def shutdown(): Future[Terminated]
}

object BoundedContext extends StrictLogging { outer =>
  import scala.concurrent.ExecutionContext.global

  private val trace = Trace( "BoundedContext", logger )

  def apply( key: Symbol ): BoundedContext = {
    contexts()
    .get( key )
    .map { _ => BoundedContextRef( key ) }
    .getOrElse { throw new BoundedContextlNotRegisteredError( key ) }
  }

  def future( key: Symbol )( implicit ec: ExecutionContext = global ): Future[BoundedContext] = {
    contexts
    .future()
    .map { cs =>
      cs
      .get( key )
      .map { _ => BoundedContextRef( key ) }
      .getOrElse { throw new BoundedContextlNotRegisteredError( key ) }
    }
  }

  def make(
    key: Symbol,
    configuration: Config,
    rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType],
    userResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    startTasks: Set[StartTask] = Set.empty[StartTask]
  )(
    implicit system: ActorSystem,
    ec: ExecutionContext = global
  ): Future[BoundedContext] = {
    contexts
    .alter { cs =>
      val cell = {
        cs
        .get( key )
        .map { c => c.copy( configuration = c.configuration withFallback configuration ) }
        .getOrElse {
          BoundedContextCell(
            key,
            system,
            DomainModelCell( key, system, rootTypes = rootTypes ),
            configuration,
            userResources,
            startTasks.toSeq
          )
        }
      }

      cs + ( key -> cell )
    }
    .map { _ => BoundedContextRef( key ) }
  }

  def unapply( bc: BoundedContext ): Option[(String, DomainModel)] = Some( (bc.name, bc.unsafeModel) )


  // bounded context resource keys
  object ResourceKeys {
    val Model = 'model
    val System = 'system
    val RootTypes = Symbol( "root-types" )
    val Configuration = 'configuration
  }


  private val contextsExecutionPool: ExecutionContext = global
  private val contexts: Agent[Map[Symbol, BoundedContextCell]] = {
    Agent( Map.empty[Symbol, BoundedContextCell] )( contextsExecutionPool )
  }

  private def sendContextCell( key: Symbol )( f: BoundedContextCell => BoundedContextCell ): Unit = {
    contexts
    .send { cells =>
      logger.debug( "LOOKING for boundedContext: [{}]", key.name )
      cells
      .get( key )
      .map { c =>
        val newCell = f( c )
        logger.debug( "SENDING key:[{}] to be BoundedContext:[{}]", key.name, newCell )
        cells + ( key -> newCell )
      }
      .getOrElse {
        logger.error( "BoundedContext not found: [{}]", BoundedContextlNotRegisteredError(key) )
        cells
      }
    }
  }

  private def alterContextCell( key: Symbol )( f: BoundedContextCell => BoundedContextCell ): Future[BoundedContextCell] = {
    implicit val ec = contextsExecutionPool
    contexts
    .alter { cells =>
      logger.debug( "LOOKING for boundedContext: [{}]", key.name )
      cells
      .get( key )
      .map { c =>
        val newCell = f( c )
        logger.debug( "ALTERING key:[{}] to be BoundedContext:[{}]", key.name, newCell )
        cells + ( key -> newCell )
      }
      .getOrElse {
        logger.error( "BoundedContext not found: [{}]", BoundedContextlNotRegisteredError(key) )
        cells
      }
    }
    .map { cells => cells( key ) }
  }

//  private def alterContextCell( key: Symbol, newCell: BoundedContextCell ): Future[BoundedContextCell] = {
//    implicit val ec = contextsExecutionPool
//    contexts
//    .alter { cells =>
//      logger.debug( "ALTERING key:[{}] to be BoundedContext:[{}]", key, newCell )
//      cells + ( key -> newCell )
//    }
//    .map { cells =>
//      require( cells(key) == newCell, s"cell[${key.name}] not updated with new context:[${newCell}]" )
//      newCell
//    }
//  }


  private[BoundedContext] object BoundedContextRef {
    def apply( key: Symbol ): BoundedContextRef = new BoundedContextRef( key )
  }

  final class BoundedContextRef private[BoundedContext]( key: Symbol ) extends BoundedContext {
    override def toString: String = s"BoundedContext(${name} system:${unsafeCell.system})"

//    private val alteringContext: ExecutionContext = global
    private def unsafeCell: BoundedContextCell = contexts()( key )
    private def futureCell: Future[BoundedContextCell] = {
      implicit val ec = contextsExecutionPool
      contexts.future() map { ctxs => ctxs( key ) }
    }

    override val name: String = key.name
    override def system: ActorSystem = unsafeCell.system
    override def unsafeModel: DomainModel = unsafeCell.unsafeModel
    override def futureModel: Future[DomainModel] = {
      implicit val ec = contextsExecutionPool
      futureCell flatMap { _.futureModel }
    }

    override def resources: Map[Symbol, Any] = unsafeCell.resources

    override def configuration: Config = unsafeCell.configuration

    override def :+( rootType: AggregateRootType ): BoundedContext = {
      outer.alterContextCell( key ){ cell =>
        val newCell = ( cell :+ rootType ).asInstanceOf[BoundedContextCell]
        logger.debug( "TEST: NEW BC CELL's ROOT-TYPES:[{}]", newCell.modelCell.rootTypes.mkString(", ") )
        newCell
      }
      this
    }

    override def withResources( rs: Map[Symbol, Any] ): BoundedContext = {
      sendContextCell( key ){ _.withResources(rs).asInstanceOf[BoundedContextCell] }
      this
    }

    override def withStartTask( task: StartTask ): BoundedContext = {
      sendContextCell( key ){ _.withStartTask( task ).asInstanceOf[BoundedContextCell] }
      this
    }

    override def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext] = {
      import scala.concurrent.Await
      import scala.concurrent.duration._

      val altered = alterContextCell( key ) { cell =>
        logger.debug( "TEST: starting cell:[{}] ec:[{}] to:[{}]", cell, implicitly[ExecutionContext], implicitly[Timeout] )
        val newCell = Await.result(cell.start().mapTo[BoundedContextCell], 10.seconds)
        logger.debug( "TEST: started... new cell:[{}]", newCell )
        newCell
      }

      altered map { _ => this }
      for { _ <- alterContextCell( key ){ cell => Await.result(cell.start().mapTo[BoundedContextCell], 10.seconds) } } yield this
    }

    override def shutdown(): Future[Terminated] = {
      implicit val ec = contextsExecutionPool
      for {
        cell <- futureCell
        terminated <- cell.shutdown()
      } yield terminated
    }
  }

  private[BoundedContext] object DomainModelRef {
    def apply( key: Symbol, system: ActorSystem ): DomainModelRef = new DomainModelRef( key, system )
  }

  final class DomainModelRef private[BoundedContext]( key: Symbol, override val system: ActorSystem ) extends DomainModel {
    override def toString: String = s"DomainModel(${name}, system:${system})"

    private def unsafeCell: DomainModelCell = contexts()( key ).modelCell
    private def futureCell: Future[DomainModelCell] = {
      implicit val ec: ExecutionContext = global
      contexts.future() map { ctxs => ctxs( key ).modelCell }
    }

    override val name: String = key.name
    override def indexBus: IndexBus = unsafeCell.indexBus
    override def rootTypes: Set[AggregateRootType] = unsafeCell.rootTypes
    override def get( rootName: String, id: Any ): Option[ActorRef] = {
      val aggregate = unsafeCell.get( rootName, id )
      logger.debug( "aggregate get([{}], [{}]) = [{}]", rootName, id.toString, aggregate )
      aggregate
    }
    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
      unsafeCell.aggregateIndexFor[K, TID, V]( rootName, indexName )
    }
  }


  final case class BoundedContextCell private[BoundedContext](
    key: Symbol,
    override val system: ActorSystem,
    modelCell: DomainModelCell,
    override val configuration: Config,
    userResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    startTasks: Seq[StartTask] = Seq.empty[StartTask],
    supervisors: Option[Supervisors] = None
  ) extends BoundedContext {
    override def toString: String = s"""BoundedContext(${name}, system:[${system}], model:[${modelCell}]"""

    override val name: String = key.name
    override val unsafeModel: DomainModel = DomainModelRef( key, system )
    override def futureModel: Future[DomainModel] = Future successful unsafeModel

    override lazy val resources: Map[Symbol, Any] = {
      userResources ++ Map(
        ResourceKeys.Model -> DomainModelRef( key, system ),
        ResourceKeys.System -> system,
        ResourceKeys.RootTypes -> modelCell.rootTypes,
        ResourceKeys.Configuration -> configuration
      )
    }

    override def :+( rootType: AggregateRootType ): BoundedContext = {
      val newModel = modelCell addAggregateType rootType
      logger.debug( "adding rootType:[{}] to modelCell - new root-types=[{}]", rootType, newModel.rootTypes.mkString(", ") )
      this.copy( modelCell = newModel )
    }

    override def withResources( rs: Map[Symbol, Any] ): BoundedContext = this.copy( userResources = userResources ++ rs )

    override def withStartTask( startTask: StartTask ): BoundedContext = {
      if ( supervisors.isDefined ) {
        Task { startTask.task( this ) }
        .unsafePerformAsync {
          case scalaz.\/-( done )=> logger.debug( "start task completed with result")
          case scalaz.-\/( ex ) => {
            logger.error( "start task failed", ex )
            throw ex
          }
        }

        this
      } else {
        this.copy( startTasks = startTasks :+ startTask )
      }
    }

    override def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext] = trace.block("start") {
      def debugBoundedContext( label: String, bc: BoundedContextCell ): Unit = {
        logger.debug(
          "{}: starting BoundedContext:[{}] root-types:[{}] user-resources:[{}] start-tasks:[{}]:[{}]...",
          label,
          (bc.name, bc.system.name),
          bc.modelCell.rootTypes map { _.name },
          bc.userResources.keySet,
          bc.startTasks.size.toString, startTasks.map{ _.description }.mkString(", ")
        )
      }
      //todo not see author start task!!!

//      implicit val ec = system.dispatcher


      debugBoundedContext( "START", this )
      val tasks = Task gatherUnordered gatherAllTasks()

      import peds.commons.concurrent._

      for {
        _ <- tasks.unsafeToFuture
      _ = logger.debug( "TEST: after tasks run" )
      _ = debugBoundedContext( "BC-CELL", this )
        supervisors <- setupSupervisors()
        repoStatus <- ( supervisors.repository ? StartProtocol.GetStatus ).mapTo[StartProtocol.StartStatus]
      _ = logger.debug( "supervisors:[{}] repo-status:[{}]", supervisors, repoStatus )
        newModelCell = modelCell.copy( supervisors = Some(supervisors) )
      _ = logger.debug( "newModelCell:[{}]", newModelCell )
        startedModelCell <- newModelCell.start()
      _ = logger.debug( "startedModelCell:[{}]", startedModelCell )
      } yield {
        logger.info( "started BoundedContext:[{}] model:[{}]", (name, system.name), startedModelCell )
        val result = this.copy( modelCell = startedModelCell, supervisors = Some( supervisors ), startTasks = Seq.empty[StartTask] )
        debugBoundedContext( "DONE", result )
        result
      }
    }

    private def gatherAllTasks(): Seq[Task[Done]] = {
      val rootTasks = modelCell.rootTypes.toSeq map { _.startTask( system ).task( this ) }
      val userTasks = startTasks map { _ task this }
      rootTasks ++ userTasks
    }

    override def shutdown(): Future[Terminated] = system.terminate()

    private def setupSupervisors()( implicit ec: ExecutionContext ): Future[Supervisors] = {
      import scala.concurrent.duration._
      val (repositoryBudget, indexBudget) = timeoutBudgets( 5.seconds )
      val repositorySupervisor = setupRepositorySupervisor( repositoryBudget )
      val indexSupervisor = setupIndexSupervisor( indexBudget )

      for {
        r <- repositorySupervisor
        i <- indexSupervisor
      } yield Supervisors( repository = r, index = i )
    }

    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout) = {
      import scala.concurrent.duration._
      val baseline = FiniteDuration( (0.9 * to.duration.toNanos).toLong, NANOSECONDS )
      ( Timeout( baseline / 1 ), Timeout( baseline / 1 ) )
    }

    private def setupRepositorySupervisor( budget: Timeout )( implicit ec: ExecutionContext ): Future[ActorRef] = {
      implicit val to = budget
      val repositorySupervisorName = name + "-repositories"

      findRepositorySupervisor( repositorySupervisorName ) flatMap { found =>
        found
        .map { repo => Future successful repo }
        .getOrElse { makeRepositorySupervisor( repositorySupervisorName, modelCell.rootTypes ) }
      }
    }

    private def findRepositorySupervisor(
      repositorySupervisorName: String
    )(
      implicit ec: ExecutionContext,
      to: Timeout
    ): Future[Option[ActorRef]] = {
      val sel = system.actorSelection( "/user/"+repositorySupervisorName )
      val asker = new AskableActorSelection( sel )
      ( asker ? new Identify(repositorySupervisorName.##) ).mapTo[ActorIdentity]
      .map { identity =>
        logger.debug( "finding repository-supervisor for [{}] = [{}]", sel, identity.ref )
        identity.ref
      }
    }

    private def makeRepositorySupervisor(
      repositorySupervisorName: String,
      rootTypes: Set[AggregateRootType]
    )(
      implicit ec: ExecutionContext,
      to: Timeout
    ): Future[ActorRef] = {
      val supervisor = futureModel map { model =>
        import scala.collection.JavaConversions._

        logger.debug(
          "making repository supervisor:[{}] with root-types:[{}] resources:[{}]",
          repositorySupervisorName,
          rootTypes.map{ _.name },
          resources.keySet
        )

        val props = RepositorySupervisor.props( model, rootTypes, resources, configuration )
        system.actorOf( props, repositorySupervisorName )
      }

      val started = for {
        s <- supervisor
        _ <- ( s ? StartProtocol.WaitForStart ).mapTo[StartProtocol.Started.type]
        status <- ( s ? StartProtocol.GetStatus ).mapTo[StartProtocol.StartStatus]
      } yield {
        logger.debug( "TEST: Repository Supervisor [{}] started - has status: [{}]", repositorySupervisorName, status )
        s
      }

      started onComplete {
        case Success(_) => {
          logger.debug(
            "started repository supervisor:[{}] for root-types:[{}]",
            repositorySupervisorName,
            rootTypes.map{ _.name }.mkString( ", " )
          )
        }

        case Failure( ex ) => {
          logger.error( "starting repository supervisor failed. look for startup-status in log", ex )
          for {
            s <- supervisor
            status <- ( s ? StartProtocol.GetStatus ).mapTo[StartProtocol.StartStatus]
          } {
            logger.error( "Starting RepositorySupervisor timed out with startup-status:[{}]", status )
          }
        }
      }

      started
    }

    private def setupIndexSupervisor( budget: Timeout )( implicit ec: ExecutionContext ): Future[ActorRef] = {
      implicit val to = budget
      val indexSupervisorName = modelCell.name + "-indexes"

      findIndexSupervisor( indexSupervisorName ) flatMap { found =>
        found
        .map { Future successful _ }
        .getOrElse { makeIndexSupervisor( indexSupervisorName ) }
      }
    }

    private def findIndexSupervisor(
      indexSupervisorName: String
    )(
      implicit ec: ExecutionContext,
      to: Timeout
    ): Future[Option[ActorRef]] = {
      val sel = system.actorSelection( "/user/"+indexSupervisorName )
      val asker = new AskableActorSelection( sel )
      ( asker ? new Identify(indexSupervisorName.##) ).mapTo[ActorIdentity]
      .map { identity =>
        logger.debug( "finding index-supervisor for [{}] = [{}]", sel, identity.ref )
        identity.ref
      }
    }

    private def makeIndexSupervisor( indexSupervisorName: String ): Future[ActorRef] = {
      Future successful { system.actorOf( IndexSupervisor.props( modelCell.indexBus ), s"${modelCell.name}-indexes" ) }
    }

  }


  final case class BoundedContextlNotRegisteredError private[demesne]( key: Symbol )
  extends NoSuchElementException( s"no BoundedContext registered for key:[${key.name}]" ) with DemesneError
}
