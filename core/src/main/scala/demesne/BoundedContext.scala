package demesne

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, Terminated}
import akka.agent.Agent
import akka.pattern.{AskableActorSelection, ask}
import akka.util.Timeout

import scalaz._, Scalaz._
import scalaz.concurrent.Task
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import peds.commons.TryV
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
  def resources: BoundedContext.Resources
  def configuration: Config
  def addAggregateType( rootType: AggregateRootType )( implicit ec: ExecutionContext ): Future[BoundedContext] = addAggregateTypes( Set(rootType) )
  def addAggregateTypes( rootTypes: Set[AggregateRootType] )( implicit ec: ExecutionContext ): Future[BoundedContext]
  def withResources( rs: BoundedContext.Resources ): BoundedContext
  def withStartTask( task: StartTask ): BoundedContext
  def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext]
  def shutdown(): Future[Terminated]
}

object BoundedContext extends StrictLogging { outer =>
  import scala.concurrent.ExecutionContext.global
  val timeoutDuration = 10.seconds


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
    userResources: Resources = Map.empty[Symbol, Any],
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


  type Resources = Map[Symbol, Any]

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
      cells
      .get( key )
      .map { c =>
        val newCell = f( c )
        logger.debug( "Sending BoundedContext for key:[{}] to be [{}]", key.name, newCell )
        cells + ( key -> newCell )
      }
      .getOrElse {
        logger.error( "BoundedContext not found: [{}]", BoundedContextlNotRegisteredError(key) )
        cells //todo ? throw error instead of log and ignore?
      }
    }
  }

  private def alterContextCell(
    key: Symbol
  )(
    f: BoundedContextCell => BoundedContextCell
  )(
    implicit ec: ExecutionContext = contextsExecutionPool
  ): Future[BoundedContextCell] = {
    contexts
    .alterOff { cells =>
      cells
      .get( key )
      .map { c =>
        val newCell = f( c )
        logger.debug( "Altering BoundedContext key:[{}] to be [{}]", key.name, newCell )
        cells + ( key -> newCell )
      }
      .getOrElse {
        logger.error( "BoundedContext not found: [{}]", BoundedContextlNotRegisteredError(key) )
        cells
      }
    }
    .map { cells => cells( key ) }
  }


  private[BoundedContext] object BoundedContextRef {
    def apply( key: Symbol ): BoundedContextRef = new BoundedContextRef( key )
  }

  final class BoundedContextRef private[BoundedContext]( key: Symbol ) extends BoundedContext {
    override def toString: String = s"BoundedContext(${name} system:${unsafeCell.system})"

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

    override def resources: Resources = unsafeCell.resources

    override def configuration: Config = unsafeCell.configuration

    override def addAggregateTypes(
      rootTypes: Set[AggregateRootType]
    )(
      implicit ec: ExecutionContext
    ): Future[BoundedContext] = {
      alterContextCell( key ){ cell =>
        Await.result( cell.addAggregateTypes(rootTypes).mapTo[BoundedContextCell], timeoutDuration )
      } map { _ =>
        this
      }
    }

    override def withResources( rs: Resources ): BoundedContext = {
      sendContextCell( key ){ _.withResources(rs).asInstanceOf[BoundedContextCell] }
      this
    }

    override def withStartTask( task: StartTask ): BoundedContext = {
      sendContextCell( key ){ _.withStartTask( task ).asInstanceOf[BoundedContextCell] }
      this
    }

    override def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext] = {
      for {
        cell <- alterContextCell( key ){ cell => Await.result(cell.start().mapTo[BoundedContextCell], timeout.duration) }
//        m <- cell.futureModel
      } yield this
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
      // logger.debug( "aggregate get([{}], [{}]) = [{}]", rootName, id.toString, aggregate )
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
    userResources: Resources = Map.empty[Symbol, Any],
    startTasks: Seq[StartTask] = Seq.empty[StartTask],
    supervisors: Option[Supervisors] = None
  ) extends BoundedContext {
    override def toString: String = s"""BoundedContext(${name}, system:[${system}], model:[${modelCell}]"""

    override val name: String = key.name
    override val unsafeModel: DomainModel = DomainModelRef( key, system )
    override def futureModel: Future[DomainModel] = Future successful unsafeModel

    override lazy val resources: Resources = {
      userResources ++
      Map(
        ResourceKeys.Model -> DomainModelRef( key, system ),
        ResourceKeys.System -> system,
        ResourceKeys.RootTypes -> modelCell.rootTypes,
        ResourceKeys.Configuration -> configuration
      )
    }

    override def withResources( rs: Resources ): BoundedContext = this.copy( userResources = userResources ++ rs )

    override def addAggregateTypes(
      rootTypes: Set[AggregateRootType]
    )(
      implicit ec: ExecutionContext
    ): Future[BoundedContext] = {
      Future successful {
        val newModel = rootTypes.foldLeft( modelCell ){ (m, rt) =>m addAggregateType rt }
        logger.info(
          "BoundedContextCell: added rootTypes:[{}] to modelCell - new root-types=[{}]",
          rootTypes,
          newModel.rootTypes.mkString(", ")
        )

        this.copy( modelCell = newModel )
      }
    }

    override def withStartTask( startTask: StartTask ): BoundedContext = {
      if ( started ) {
        startTask
        .task( this )
        .unsafePerformAsync {
          case scalaz.\/-( result )=> logger.debug( "start task completed with result: [{}]", result )
          case scalaz.-\/( ex ) => { logger.error( "start task failed", ex ); throw ex }
        }

        this
      } else {
        this.copy( startTasks = startTasks :+ startTask )
      }
    }

    def started: Boolean = supervisors.isDefined


    override def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[BoundedContext] = {
      import peds.commons.concurrent._

      for {
        taskResults <- this.gatherAllTasks().unsafeToFuture
        _ = logger.info( "BoundedContext start tasks results: [{}]", taskResults )
        supervisors <- setupSupervisors()
        _ <- ( supervisors.repository ? StartProtocol.WaitForStart ).mapTo[StartProtocol.Started.type]
        dupRoots = taskResults.rootTypes intersect modelCell.rootTypes
        _ = if ( dupRoots.nonEmpty ) {
          logger.info( "BoundedContext start task resulting in duplicate root types :[{}]", dupRoots.mkString(", ") )
        }
        freshRoots = taskResults.rootTypes -- dupRoots
        updatedRoots = modelCell.rootTypes ++ freshRoots
        newModelCell = modelCell.copy( supervisors = Some(supervisors), rootTypes = updatedRoots )
        startedModelCell <- newModelCell.start()
        _ = logger.info( "BoundedContext started modeCell root types:[{}]", startedModelCell.rootTypes )
      } yield {
        logger.info( "started BoundedContext:[{}] model:[{}]", (name, system.name), startedModelCell )
        val dupResources = taskResults.resources.keySet intersect userResources.keySet
        if ( dupResources.nonEmpty ) {
          logger.warn(
            "duplicate resources resulting from start tasks replacing user provided: [{}]",
            dupResources.mkString(", ")
          )
        }

        this.copy(
          modelCell = startedModelCell,
          supervisors = Some(supervisors),
          startTasks = Seq.empty[StartTask],
          userResources = userResources ++ taskResults.resources
        )
      }
    }


    private def gatherAllTasks(): Task[StartTask.Result] = {
      val rootTasks = modelCell.rootTypes.toSeq map { _.startTask.task( this ) }
      val userTasks = startTasks map { _ task this }
      val all = ( rootTasks ++ userTasks )

//todo consider reducer and reduceUnordered
//      val reducer = new Reducer[]
//      Task.reduceUnordered( all, true )( )
      Task
      .gatherUnordered( all )
      .map { results =>
        val aggregate = results.foldLeft( StartTask.Result() ){ (acc, r) =>
          val dups = r.resources.keySet intersect acc.resources.keySet
          if ( dups.nonEmpty ) {
            logger.warn( "duplicate resources resulting from start tasks - selection undefined: [{}]", dups.mkString(", ") )
          }

          acc.copy(
            resources = acc.resources ++ r.resources,
            rootTypes = acc.rootTypes ++ r.rootTypes
          )
        }

        logger.info(
          "BoundedContext[{}]: {} Start tasks completed with resources:[{}] and new root-types:[{}]",
          name,
          all.size.toString,
          aggregate.resources.mkString(", "),
          aggregate.rootTypes.mkString(", ")
        )

        aggregate
      }
      .onFinish { ex =>
        ex foreach { x => logger.error( s"BoundedContext[${name}]: at least one start task failed", x ) }
        Task now { () }
      }
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
        logger.debug( "Repository Supervisor [{}] started - has status: [{}]", repositorySupervisorName, status )
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
