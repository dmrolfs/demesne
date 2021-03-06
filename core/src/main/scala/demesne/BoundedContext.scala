package demesne

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor.{ ActorIdentity, ActorRef, ActorSystem, Identify, Terminated }
import akka.agent.Agent
import akka.pattern.{ ask, AskableActorSelection }
import akka.util.Timeout
import shapeless.the
import monix.eval.Task
import monix.execution.Scheduler
import com.typesafe.config.Config
import omnibus.core.{ EC, ErrorOr }
import demesne.repository.{ RepositorySupervisor, StartProtocol }
import demesne.DomainModel.{ AggregateIndex, DomainModelCell, Supervisors }
import demesne.index.{ IndexBus, IndexSupervisor }

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

  def addAggregateType[_: EC]( rootType: AggregateRootType ): Future[BoundedContext] =
    addAggregateTypes( Set( rootType ) )
  def addAggregateTypes[_: EC]( rootTypes: Set[AggregateRootType] ): Future[BoundedContext]
  def withResources( rs: BoundedContext.Resources ): BoundedContext
  def withStartTask[_: EC]( task: StartTask ): BoundedContext
  def start[_: EC: TO](): Future[BoundedContext]
  def shutdown(): Future[Terminated]
}

object BoundedContext { outer =>
  import Scheduler.global

  def toScheduler[_: EC]: Scheduler = Scheduler apply the[ExecutionContext]

  val timeoutDuration = 10.seconds

  def apply( key: Symbol ): BoundedContext = {
    contexts()
      .get( key )
      .map { _ =>
        BoundedContextRef( key )
      }
      .getOrElse { throw new BoundedContextlNotRegisteredError( key ) }
  }

  def future( key: Symbol )( implicit ec: ExecutionContext = global ): Future[BoundedContext] = {
    contexts
      .future()
      .map { cs =>
        cs.get( key )
          .map { _ =>
            BoundedContextRef( key )
          }
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
          cs.get( key )
            .map { c =>
              c.copy( configuration = c.configuration withFallback configuration )
            }
            .getOrElse {
              BoundedContextCell(
                key,
                system,
                DomainModelCell( key, system, configuration, rootTypes = rootTypes ),
                configuration,
                userResources,
                startTasks.toSeq
              )
            }
        }

        cs + (key -> cell)
      }
      .map { _ =>
        BoundedContextRef( key )
      }
  }

  def unapply( bc: BoundedContext ): Option[( String, DomainModel )] =
    Some( ( bc.name, bc.unsafeModel ) )

  type Resources = Map[Symbol, Any]

  // bounded context resource keys
  object ResourceKeys {
    val Model = 'model
    val System = 'system
    val RootTypes = Symbol( "root-types" )
    val Configuration = 'configuration
  }

  private val contextsScheduler: ExecutionContext = global

  private val contexts: Agent[Map[Symbol, BoundedContextCell]] = {
    Agent( Map.empty[Symbol, BoundedContextCell] )( contextsScheduler )
  }

  private def sendContextCell(
    key: Symbol
  )( f: BoundedContextCell => BoundedContextCell ): Unit = {
    contexts
      .send { cells =>
        cells
          .get( key )
          .map { c =>
            val newCell = f( c )
            scribe.debug( s"Sending BoundedContext for key:[${key.name}] to be [${newCell}]" )
            cells + (key -> newCell)
          }
          .getOrElse {
            scribe.error(
              s"BoundedContext not found: [${BoundedContextlNotRegisteredError( key )}]"
            )
            cells //todo ? throw error instead of log and ignore?
          }
      }
  }

  private def alterContextCell[_: EC](
    key: Symbol
  )(
    f: BoundedContextCell => BoundedContextCell
  ): Future[BoundedContextCell] = {
    contexts
      .alterOff { cells =>
        cells
          .get( key )
          .map { c =>
            val newCell = f( c )
            scribe.debug( s"Altering BoundedContext key:[${key.name}] to be [${newCell}]" )
            cells + (key -> newCell)
          }
          .getOrElse {
            scribe.error(
              s"BoundedContext not found: [${BoundedContextlNotRegisteredError( key )}]"
            )
            cells
          }
      }
      .map { cells =>
        cells( key )
      }
  }

  private[BoundedContext] object BoundedContextRef {
    def apply( key: Symbol ): BoundedContextRef = new BoundedContextRef( key )
  }

  final class BoundedContextRef private[BoundedContext] ( key: Symbol ) extends BoundedContext {
    override def toString: String = s"BoundedContext(${name} system:${unsafeCell.system})"

    private def unsafeCell: BoundedContextCell = contexts()( key )
    private def futureCell: Future[BoundedContextCell] = {
      implicit val ec = contextsScheduler
      contexts.future() map { ctxs =>
        ctxs( key )
      }
    }

    override val name: String = key.name
    override def system: ActorSystem = unsafeCell.system
    override def unsafeModel: DomainModel = unsafeCell.unsafeModel
    override def futureModel: Future[DomainModel] = {
      implicit val ev = contextsScheduler
      futureCell flatMap { _.futureModel }
    }

    override def resources: Resources = unsafeCell.resources

    override def configuration: Config = unsafeCell.configuration

    override def addAggregateTypes[_: EC](
      rootTypes: Set[AggregateRootType]
    ): Future[BoundedContext] = {
      alterContextCell( key ) { cell =>
        Await.result(
          cell.addAggregateTypes( rootTypes ).mapTo[BoundedContextCell],
          timeoutDuration
        )
      } map { _ =>
        this
      }
    }

    override def withResources( rs: Resources ): BoundedContext = {
      sendContextCell( key ) { _.withResources( rs ).asInstanceOf[BoundedContextCell] }
      this
    }

    override def withStartTask[_: EC]( task: StartTask ): BoundedContext = {
      sendContextCell( key ) { _.withStartTask( task ).asInstanceOf[BoundedContextCell] }
      this
    }

    override def start[_: EC: TO](): Future[BoundedContext] = {
      for {
        cell <- alterContextCell( key ) { cell =>
          Await.result( cell.start().mapTo[BoundedContextCell], the[Timeout].duration )
        }
      } yield this
    }

    override def shutdown(): Future[Terminated] = {
      implicit val ec = contextsScheduler
      for {
        cell       <- futureCell
        terminated <- cell.shutdown()
      } yield terminated
    }
  }

  private[BoundedContext] object DomainModelRef {

    def apply( key: Symbol, system: ActorSystem ): DomainModelRef =
      new DomainModelRef( key, system )
  }

  final class DomainModelRef private[BoundedContext] (
    key: Symbol,
    override val system: ActorSystem
  ) extends DomainModel {
    override def toString: String = s"DomainModel(${name}, system:${system})"

    private def unsafeCell: DomainModelCell = contexts()( key ).modelCell
//    private def futureCell: Future[DomainModelCell] = {
//      implicit val ec: ExecutionContext = global
//      contexts.future() map { ctxs =>
//        ctxs( key ).modelCell
//      }
//    }

    override val name: String = key.name
    override def rootTypes: Set[AggregateRootType] = unsafeCell.rootTypes
    override def configuration: Config = unsafeCell.configuration
    override def indexBus: IndexBus = unsafeCell.indexBus

    override def get( rootName: String, id: Any ): Option[ActorRef] = {
      val aggregate = unsafeCell.get( rootName, id )
      // logger.debug( "aggregate get([{}], [{}]) = [{}]", rootName, id.toString, aggregate )
      aggregate
    }

    override def aggregateIndexFor[K, TID, V](
      rootName: String,
      indexName: Symbol
    ): ErrorOr[AggregateIndex[K, TID, V]] = {
      unsafeCell.aggregateIndexFor[K, TID, V]( rootName, indexName )
    }
  }

  final case class BoundedContextCell private[BoundedContext] (
    key: Symbol,
    override val system: ActorSystem,
    modelCell: DomainModelCell,
    override val configuration: Config,
    userResources: Resources = Map.empty[Symbol, Any],
    startTasks: Seq[StartTask] = Seq.empty[StartTask],
    supervisors: Option[Supervisors] = None
  ) extends BoundedContext {
//    implicit def toScheduler[_: EC]: Scheduler = BoundedContext toScheduler the[ExecutionContext]

    override def toString: String =
      s"""BoundedContext(${name}, system:[${system}], model:[${modelCell}]"""

    override val name: String = key.name
    override val unsafeModel: DomainModel = DomainModelRef( key, system )
    override def futureModel: Future[DomainModel] = Future successful unsafeModel

    override lazy val resources: Resources = {
      userResources ++
      Map(
        ResourceKeys.Model         -> DomainModelRef( key, system ),
        ResourceKeys.System        -> system,
        ResourceKeys.RootTypes     -> modelCell.rootTypes,
        ResourceKeys.Configuration -> configuration
      )
    }

    override def withResources( rs: Resources ): BoundedContext =
      this.copy( userResources = userResources ++ rs )

    override def addAggregateTypes[_: EC](
      rootTypes: Set[AggregateRootType]
    ): Future[BoundedContext] = {
      Future successful {
        val newModel = rootTypes.foldLeft( modelCell ) { ( m, rt ) =>
          m addAggregateType rt
        }
        scribe.info(
          s"BoundedContextCell: added rootTypes:[${rootTypes}] to modelCell - new root-types=[${newModel.rootTypes
            .mkString( ", " )}]",
        )

        this.copy( modelCell = newModel )
      }
    }

    override def withStartTask[_: EC]( startTask: StartTask ): BoundedContext = {
      implicit val scheduler = toScheduler( the[ExecutionContext] )

      if (started) {
        startTask
          .task( this )
          .runAsync
          .onComplete {
            case Success( result ) => {
              scribe.debug( s"start task completed with result: [${result}]" )
            }

            case Failure( ex ) => {
              scribe.error( "start task failed", ex )
              throw ex
            }
          }

        this
      } else {
        this.copy( startTasks = startTasks :+ startTask )
      }
    }

    def started: Boolean = supervisors.isDefined

    override def start[_: EC: TO](): Future[BoundedContext] = {
      import omnibus.commons.concurrent._

      implicit val scheduler = toScheduler( the[ExecutionContext] )

      for {
        taskResults <- this.gatherAllTasks().unsafeToFuture
        _ = scribe.info( s"BoundedContext start tasks results: [${taskResults}]" )
        supervisors <- setupSupervisors()
        _           <- (supervisors.repository ? StartProtocol.WaitForStart).mapTo[StartProtocol.Started.type]
        dupRoots = taskResults.rootTypes intersect modelCell.rootTypes
        _ = if (dupRoots.nonEmpty) {
          scribe.info(
            s"BoundedContext start task resulting in duplicate root types:[${dupRoots.mkString( ", " )}]"
          )
        }
        freshRoots = taskResults.rootTypes -- dupRoots
        updatedRoots = modelCell.rootTypes ++ freshRoots
        newModelCell = modelCell.copy( supervisors = Some( supervisors ), rootTypes = updatedRoots )
        startedModelCell <- newModelCell.start()
        _ = scribe.info(
          s"BoundedContext started modeCell root types:[${startedModelCell.rootTypes}]"
        )
      } yield {
        scribe.info(
          s"started BoundedContext:[${( name, system.name )}] model:[${startedModelCell}]"
        )
        val dupResources = taskResults.resources.keySet intersect userResources.keySet
        if (dupResources.nonEmpty) {
          scribe.warn(
            s"duplicate resources resulting from start tasks replacing user provided: [${dupResources
              .mkString( ", " )}]"
          )
        }

        this.copy(
          modelCell = startedModelCell,
          supervisors = Some( supervisors ),
          startTasks = Seq.empty[StartTask],
          userResources = userResources ++ taskResults.resources
        )
      }
    }

    private def gatherAllTasks(): Task[StartTask.Result] = {
      val rootTasks = modelCell.rootTypes.toSeq map { _.startTask.task( this ) }
      val userTasks = startTasks map { _ task this }
      val all = (rootTasks ++ userTasks)

//todo consider reducer and reduceUnordered
//      val reducer = new Reducer[]
//      Task.reduceUnordered( all, true )( )
      Task
        .gatherUnordered( all )
        .map { results =>
          val aggregate = results.foldLeft( StartTask.Result() ) { ( acc, r ) =>
            val dups = r.resources.keySet intersect acc.resources.keySet
            if (dups.nonEmpty) {
              scribe.warn(
                s"duplicate resources resulting from start tasks - selection undefined: [${dups.mkString( ", " )}]"
              )
            }

            acc.copy(
              resources = acc.resources ++ r.resources,
              rootTypes = acc.rootTypes ++ r.rootTypes
            )
          }

          scribe.info(
            s"BoundedContext[${name}]: ${all.size.toString} Start tasks completed with resources:[${aggregate.resources
              .mkString( ", " )}] and new root-types:[${aggregate.rootTypes.mkString( ", " )}]"
          )

          aggregate
        }
        .doOnFinish { ex =>
          ex foreach { x =>
            scribe.error( s"BoundedContext[${name}]: at least one start task failed", x )
          }
          Task now { () }
        }
    }

    override def shutdown(): Future[Terminated] = {
      implicit val ec = contextsScheduler

      for {
        _ <- modelCell.shutdown
        s <- system.terminate()
      } yield s
    }

    private def setupSupervisors[_: EC](): Future[Supervisors] = {
      import scala.concurrent.duration._
      val ( repositoryBudget, indexBudget ) = timeoutBudgets( 5.seconds )
      val repositorySupervisor = setupRepositorySupervisor( repositoryBudget )
      val indexSupervisor = setupIndexSupervisor( indexBudget )

      for {
        r <- repositorySupervisor
        i <- indexSupervisor
      } yield Supervisors( repository = r, index = i )
    }

    private def timeoutBudgets( to: Timeout ): ( Timeout, Timeout ) = {
      import scala.concurrent.duration._
      val baseline = FiniteDuration( (0.9 * to.duration.toNanos).toLong, NANOSECONDS )
      ( Timeout( baseline / 1 ), Timeout( baseline / 1 ) )
    }

    private def setupRepositorySupervisor[_: EC]( budget: Timeout ): Future[ActorRef] = {
      implicit val to = budget
      val repositorySupervisorName = name + "-repositories" //todo would like to change this but attempt below fails
//      val repositorySupervisorName = name + ":repositories"

      findRepositorySupervisor( repositorySupervisorName ) flatMap { found =>
        found
          .map { repo =>
            Future successful repo
          }
          .getOrElse { makeRepositorySupervisor( repositorySupervisorName, modelCell.rootTypes ) }
      }
    }

    private def findRepositorySupervisor[_: EC: TO](
      repositorySupervisorName: String
    ): Future[Option[ActorRef]] = {
      val sel = system.actorSelection( "/user/" + repositorySupervisorName )
      val asker = new AskableActorSelection( sel )
      (asker ? new Identify( repositorySupervisorName.## ))
        .mapTo[ActorIdentity]
        .map { identity =>
          scribe.debug( s"finding repository-supervisor for [${sel}] = [${identity.ref}]" )
          identity.ref
        }
    }

    private def makeRepositorySupervisor[_: EC: TO](
      repositorySupervisorName: String,
      rootTypes: Set[AggregateRootType]
    ): Future[ActorRef] = {
      val supervisor = futureModel map { model =>
        import scala.collection.JavaConversions._

        scribe.debug(
          s"making repository supervisor:[${repositorySupervisorName}] with root-types:[${rootTypes
            .map { _.name }}] resources:[${resources.keySet}]"
        )

        val props = RepositorySupervisor.props( model, rootTypes, resources, configuration )
        system.actorOf( props, repositorySupervisorName )
      }

      val started = for {
        s      <- supervisor
        _      <- (s ? StartProtocol.WaitForStart).mapTo[StartProtocol.Started.type]
        status <- (s ? StartProtocol.GetStatus).mapTo[StartProtocol.StartStatus]
      } yield {
        scribe.debug(
          s"Repository Supervisor [${repositorySupervisorName}] started - has status: [${status}]"
        )
        s
      }

      started onComplete {
        case Success( _ ) => {
          scribe.debug(
            s"started repository supervisor:[${repositorySupervisorName}] for root-types:[${rootTypes.map { _.name }.mkString( ", " )}]"
          )
        }

        case Failure( ex ) => {
          scribe.error(
            "starting repository supervisor failed. look for startup-status in log",
            ex
          )
          for {
            s      <- supervisor
            status <- (s ? StartProtocol.GetStatus).mapTo[StartProtocol.StartStatus]
          } {
            scribe.error(
              s"Starting RepositorySupervisor timed out with startup-status:[${status}]"
            )
          }
        }
      }

      started
    }

    private def setupIndexSupervisor[_: EC]( budget: Timeout ): Future[ActorRef] = {
      implicit val to = budget
      val indexSupervisorName = modelCell.name + "-indexes"

      findIndexSupervisor( indexSupervisorName ) flatMap { found =>
        found
          .map { Future successful _ }
          .getOrElse { makeIndexSupervisor( indexSupervisorName ) }
      }
    }

    private def findIndexSupervisor[_: EC: TO](
      indexSupervisorName: String
    ): Future[Option[ActorRef]] = {
      val sel = system.actorSelection( "/user/" + indexSupervisorName )
      val asker = new AskableActorSelection( sel )
      (asker ? new Identify( indexSupervisorName.## ))
        .mapTo[ActorIdentity]
        .map { identity =>
          scribe.debug( s"finding index-supervisor for [${sel}] = [${identity.ref}]" )
          identity.ref
        }
    }

    private def makeIndexSupervisor( indexSupervisorName: String ): Future[ActorRef] = {
      Future successful {
        system.actorOf( IndexSupervisor.props( modelCell.indexBus ), indexSupervisorName )
      }
    }
  }

  final case class BoundedContextlNotRegisteredError private[demesne] ( key: Symbol )
      extends NoSuchElementException( s"no BoundedContext registered for key:[${key.name}]" )
      with DemesneError
}
