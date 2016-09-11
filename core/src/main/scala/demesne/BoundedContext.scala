package demesne

import scala.concurrent.{ExecutionContext, Future}
import akka.Done
import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, Terminated}
import akka.agent.Agent
import akka.pattern.{ ask, AskableActorSelection }
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
  def unsafeModel: DomainModel
  def futureModel: Future[DomainModel]
  def resources: Map[Symbol, Any]
  def :+( rootType: AggregateRootType ): BoundedContext
  def +:( rootType: AggregateRootType ): BoundedContext = this :+ rootType
  def withResources( rs: Map[Symbol, Any] ): BoundedContext
  def withStartTask( task: BoundedContext.StartTask ): BoundedContext
  def withStartTask( task: BoundedContext => Done, description: String = "" ): BoundedContext = {
    withStartTask( BoundedContext.StartTask(task, description) )
  }
  def start()( implicit ec: ExecutionContext ): Future[BoundedContext]
  def shutdown(): Future[Terminated]
}

object BoundedContext extends StrictLogging { outer =>
  import scala.concurrent.ExecutionContext.global

  private val trace = Trace( "BoundedContext", logger )

  final case class StartTask( task: BoundedContext => Done, description: String = "" )


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
  val ModelResource = 'model
  val SystemResource = 'system
  val RootTypesResource = Symbol( "root-types" )
  val ConfigurationResource = 'configuration


  private val contexts: Agent[Map[Symbol, BoundedContextCell]] = Agent( Map.empty[Symbol, BoundedContextCell] )( global )

  private def alterContextCell(
    key: Symbol
  )(
    f: BoundedContextCell => BoundedContextCell
  )(
    implicit ec: ExecutionContext
  ): Future[BoundedContextCell] = {
    contexts
    .alter { cells =>
      logger.debug( "LOOKING for boundedContext: [{}]", key.name )
      cells.get( key )
      .map { cell =>
        val newCell = f( cell )
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

  private def alterContextCell(
    key: Symbol,
    newCell: BoundedContextCell
  )(
    implicit ec: ExecutionContext
  ): Future[BoundedContextCell] = {
    contexts
    .alter { cells =>
      logger.debug( "ALTERING key:[{}] to be BoundedContext:[{}]", key, newCell )
      cells + ( key -> newCell )
    }
    .map { cells =>
      require( cells(key) == newCell, s"cell[${key.name}] not updated with new context:[${newCell}]" )
      newCell
    }
  }


  private[BoundedContext] object BoundedContextRef {
    def apply( key: Symbol ): BoundedContextRef = new BoundedContextRef( key )
  }

  final class BoundedContextRef private[BoundedContext]( key: Symbol ) extends BoundedContext {
    override def toString: String = s"BoundedContext(${name} system:${unsafeCell.system})"

    private val alteringContext: ExecutionContext = global
    private def unsafeCell: BoundedContextCell = contexts()( key )
    private def futureCell: Future[BoundedContextCell] = {
      implicit val ec = alteringContext
      contexts.future() map { ctxs => ctxs( key ) }
    }

    override val name: String = key.name
    override def unsafeModel: DomainModel = unsafeCell.unsafeModel
    override def futureModel: Future[DomainModel] = {
      implicit val ec = alteringContext
      futureCell flatMap { _.futureModel }
    }

    override def resources: Map[Symbol, Any] = unsafeCell.resources

    override def :+( rootType: AggregateRootType ): BoundedContext = {
      implicit val ec = alteringContext

      for {
        cell <- futureCell
        newCell = ( cell :+ rootType ).asInstanceOf[BoundedContextCell]
        _ = logger.debug( "TEST: NEW BC CELL's ROOT-TYPES:[{}]", newCell.modelCell.rootTypes.mkString(", ") )
        altered <- outer.alterContextCell( key, newCell )
      } yield Done

      this
    }

    override def withResources( rs: Map[Symbol, Any] ): BoundedContext = {
      implicit val ec = alteringContext
      futureCell foreach { cell =>
        outer.alterContextCell( key, newCell = cell.withResources(rs).asInstanceOf[BoundedContextCell] )( alteringContext )
      }
      this
    }

    override def withStartTask( task: StartTask ): BoundedContext = {
      implicit val ec = alteringContext
      futureCell foreach { cell =>
        outer.alterContextCell(
          key,
          cell.withStartTask( task ).asInstanceOf[BoundedContextCell]
        )(
          alteringContext
        )
      }
      this
    }

    override def start()( implicit ec: ExecutionContext ): Future[BoundedContext] = {
      implicit val ec = alteringContext

      for {
        cell <- futureCell
        started <- cell.start().mapTo[BoundedContextCell]
        _ <- outer.alterContextCell( key, started )
      } yield {
        BoundedContextRef( key )
      }
    }

    override def shutdown(): Future[Terminated] = {
      implicit val ec = alteringContext
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
    override def get( rootName: String, id: Any ): Option[ActorRef] = unsafeCell.get( rootName, id )
    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
      unsafeCell.aggregateIndexFor[K, TID, V]( rootName, indexName )
    }
  }



  final case class BoundedContextCell private[BoundedContext](
    key: Symbol,
    system: ActorSystem,
    modelCell: DomainModelCell,
    configuration: Config,
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
        ModelResource -> DomainModelRef( key, system ),
        SystemResource -> system,
        RootTypesResource -> modelCell.rootTypes,
        ConfigurationResource -> configuration
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

    override def start()( implicit ec: ExecutionContext ): Future[BoundedContext] = trace.block("start") {
      import scala.concurrent.duration._

      def debugBoundedContext( label: String, bc: BoundedContextCell ): Unit = {
        logger.debug(
          "{}: starting BoundedContext:[{}] root-types:[{}] user-resources:[{}] start-tasks:[{}]:[{}] configuration:[{}]...",
          label,
          (bc.name, bc.system.name),
          bc.modelCell.rootTypes.mkString(", "),
          bc.userResources.mkString(", "),
          bc.startTasks.size.toString, startTasks.map{ _.description }.mkString(", "),
          bc.configuration
        )
      }
      //todo not see author start task!!!

      implicit val ec = system.dispatcher
      implicit val timeout = Timeout( 3.seconds )

      import peds.commons.concurrent.TaskExtensionOps

      debugBoundedContext( "START", this )
      val tasks = new TaskExtensionOps( Task gatherUnordered startTasks.map{ t => Task { t.task(this) } } )

      val result = for {
        _ <- tasks.runFuture()
        bcCell <- contexts.future map { _(key) }
        _ = debugBoundedContext( "BC-CELL", bcCell )
        supervisors <- bcCell.setupSupervisors()
        newModelCell = bcCell.modelCell.copy( supervisors = Some(supervisors) )
        startedModelCell <- newModelCell.start()
      } yield {
        logger.info( "started BoundedContext:[{}] model:[{}]", (name, system.name), startedModelCell )
        bcCell.copy( modelCell = startedModelCell, supervisors = Some( supervisors ), startTasks = Seq.empty[StartTask] )
      }

      result foreach { r => debugBoundedContext( "DONE", r ) }
      result
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
      val baseline = to.duration - 100.millis
      ( Timeout( baseline / 2 ), Timeout( baseline / 2 ) )
    }

    private def setupRepositorySupervisor( budget: Timeout )( implicit ec: ExecutionContext ): Future[ActorRef] = {
      implicit val to = budget
      val repositorySupervisorName = name + "-repositories"

      findRepositorySupervisor( repositorySupervisorName ) flatMap { found =>
        found
        .map { Future successful _ }
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
      for {
        model <- futureModel
        props = RepositorySupervisor.props( model, rootTypes, resources, configuration )
        supervisor = system.actorOf( props, repositorySupervisorName )
        _ <- ( supervisor ? StartProtocol.WaitForStart )
      } yield {
        logger.debug(
          "started repository supervisor:[{}] for root-types:[{}]",
          repositorySupervisorName,
          rootTypes.map{ _.name }.mkString( ", " )
        )

        supervisor
      }
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




/////////////////////////////////////////////////////////

//object BoundedContext extends LazyLogging {
//  def apply( key: Symbol ): BoundedContext = {
//    registry()
//    .get( key )
//    .map { cell => BoundedContextRef( key, cell ) }
//    .getOrElse { throw new BoundedContextlNotRegisteredError( key ) }
//  }

//  def apply( key: Symbol, configuration: Config )( implicit system: ActorSystem ): BoundedContext = {
//    val cell = {
//      registry.get( )
//      .get( key )
//      .map { c => c.copy( configuration = c.configuration withFallback configuration ) }
//      .getOrElse {
//        val model = DomainModelCell( key, system, new IndexBus )
//        BoundedContextCell( key, system, model, configuration )
//      }
//    }
//
//    registry send { _ + ( key -> cell ) }
//    BoundedContextRef( key, cell )
//  }
//
//  def future( key: Symbol ): Future[BoundedContext] = {
//    registry
//    .future()
//    .map { reg =>
//      reg
//      .get( key )
//      .map { cell => BoundedContextRef( key, cell ) }
//      .getOrElse { throw new BoundedContextlNotRegisteredError( key ) }
//    }
//  }



//  private def updateCell(
//    key: Symbol
//  )(
//    fn: BoundedContextCell => BoundedContextCell
//  )(
//    implicit ec: ExecutionContext
//  ): Future[BoundedContextCell] = {
//    registry
//    .alter { cells =>
//      logger.debug( "LOOKING for boundedContext: [{}]", key )
//      cells.get( key )
//      .map { cell =>
//        val newCell = fn( cell )
//        logger.debug( "ALTERING key:[{}] to be BoundedContext:[{}]", key, newCell )
//        cells + ( key -> newCell )
//      }
//      .getOrElse {
//        logger.error( "BoundedContext not found: [{}]", BoundedContextlNotRegisteredError(key) )
//        cells
//      }
//    }
//    .map { cells => cells( key ) }
//  }



//  object BoundedContextRef {
//    def apply( key: Symbol, cell: BoundedContextCell ): BoundedContext = new BoundedContextRef( key, cell )
//  }
//
//  final class BoundedContextRef private[BoundedContext]( key: Symbol, cell: BoundedContextCell ) extends BoundedContext {
//    //  def system: ActorSystem
//    override def name: String = key.name
//    override def model: DomainModel = cell.model
////    override def futureModel: Future[DomainModel] = cell.futureModel
//    override def :+( rootType: AggregateRootType ): BoundedContext = ???
//    override def withResources( resources: Map[Symbol, Any] ): BoundedContext = ???
//    override def withStartTask( task: Task[Done] ): BoundedContext = ???
//    override def start(): Future[DomainModel] = ???
//    override def shutdown(): Future[Terminated] = cell.shutdown()
//  }


//  final case class Supervisors private[BoundedContext]( repository: ActorRef, index: ActorRef )


////  final case class BoundedContextCell private[BoundedContext](
////    key: Symbol,
////    system: ActorSystem,
////    modelCell: DomainModelCell,
////    configuration: Config,
////    supervisors: Option[Supervisors] = None,
////    userResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
////    startTasks: Seq[Task[Done]] = Seq.empty[Task[Done]]
////  ) extends BoundedContext with Equals {
//    override def toString: String = s"""BoundedContext(${name}, system:[${system}], model:[${modelCell}]"""
//
//    override def name: String = key.name
//
//    override def model: DomainModel = DomainModelRef( key, system )
//
//    override def :+( rootType: AggregateRootType ): BoundedContext = trace.block(s":+ [${rootType.name}]") {
//      val newModel = modelCell addAggregateType rootType
//      logger.debug( "in updateModelCell - added rootType:[{}] to domain-model-cell:[{}]", rootType.name, newModel )
//      this.copy( modelCell = newModel )
//    }

//    override def withResources( resources: Map[Symbol, Any] ): BoundedContext = {
//      this.copy( userResources = userResources ++ resources )
//    }
//
//    override def withStartTask( task: Task[Done] ): BoundedContext = this.copy( startTasks = startTasks :+ task )

//    private def updateModelCell(
//      fn: DomainModelCell => DomainModelCell
//    )(
//      implicit ec: ExecutionContext
//    ): Future[DomainModelCell] = trace.block("updateModelCell") {
//      modelRegistry
//      .alter { models =>
//        logger.debug( "LOOKING for model cell:[{}]", key )
//        models.get( key )
//        .map { m =>
//          logger.debug( "ALTERING - key:[{}] found:[{}]", key, m )
//          models + ( key -> fn(m) )
//        }
//        .getOrElse { models }
//      }
//      .map { models => models( key ) }
//    }

//    override def start(): Future[DomainModel] = {
//      import scala.concurrent.duration._
//      import akka.pattern.AskableActorSelection
//
//      logger.debug( "starting BoundedContext:[{}]...", (name, system.name) )
//      implicit val ec = system.dispatcher
//      implicit val timeout = Timeout( 3.seconds )
//      val repositorySupervisorName = name + "-repositories"
//      val sel = system.actorSelection( "/user/"+repositorySupervisorName )
//      val asker = new AskableActorSelection( sel )
//      val result = DomainModelRef( key, system )
//
//      import peds.commons.concurrent.TaskExtensionOps
//
//      val tasks = new TaskExtensionOps( Task.gatherUnordered( startTasks ) )
//
//      for {
//        _ <- tasks.runFuture()
//        _ <- verifySupervisor()
//      } yield {
//        logger.info( "started BoundedContext:[{}]", (name, system.name) )
//        result
//      }
//    }

//    override def shutdown(): Future[Terminated] = system.terminate()


//    private def setupSupervisors(): Future[Supervisors] = {
//      for {
//        cell <- modelCell()
//        _ = logger.debug( "BC: making repo supervisor" )
//        indexSupervisor <- makeIndexSupervisor( cell )
//        repoSupervisor <- makeRepositorySupervisor( repositorySupervisorName, cell.rootTypes, userResources )
//        _ = logger.debug( "BC: updating model cell" )
//        newCell <- updateModelCell {
//          _.copy( indexSupervisor = Some(indexSupervisor), repositorySupervisor = Some(repoSupervisor) )
//        }
//        _ = logger.debug( "BC: starting model cell = [{}]", newCell )
//        _ <- newCell.start()
//        _ = logger.debug( "BC: model cell started" )
//      } yield {
//        Done
//      }
//    }

//    private def verifySupervisor(): Future[Done] = {
//
//      ( asker ? new Identify( repositorySupervisorName.## ) ).mapTo[ActorIdentity] flatMap { identity =>
//        identity.ref
//        .map { _ =>
//          logger.debug( "BC: repository supervisor:[{}] found created" )
//          Future successful Done
//        }
//        .getOrElse { setupSupervisors() }
//      }
//    }


//    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout) = {
//      import scala.concurrent.duration._
//      val baseline = to.duration - 100.millis
//      ( Timeout( baseline / 2 ), Timeout( baseline / 2 ) )
//    }

//    private def modelCell()( implicit ec: ExecutionContext ): Future[DomainModelCell] = {
//      modelRegistry.future() map {
//        _.get( key ) getOrElse { throw new DomainModelNotRegisteredError(name, system) }
//      }
//    }

//    private def makeRepositorySupervisor(
//      repositorySupervisorName: String,
//      rootTypes: Set[AggregateRootType],
//      userResources: Map[Symbol, Any]
//    )(
//      implicit to: Timeout
//    ): Future[ActorRef] = {
//      import akka.pattern.ask
//      implicit val ec = system.dispatcher
//      val props = RepositorySupervisor.props( model, rootTypes, userResources, configuration )
//      val supervisor = system.actorOf( props, repositorySupervisorName )
//      ( supervisor ? StartProtocol.WaitForStart ) map { _ =>
//        logger.debug(
//          "started repository supervisor:[{}] for root-types:[{}]",
//          repositorySupervisorName,
//          rootTypes.map{ _.name }.mkString( ", " )
//        )
//
//        supervisor
//      }
//    }

//    private def makeIndexSupervisor( model: DomainModel ): Future[ActorRef] = {
//      Future successful { system.actorOf( IndexSupervisor.props( model.indexBus ), s"${model.name}-indexes" ) }
//    }
//  }


//  final case class DomainModelRef private[BoundedContext](
//    key: Symbol,
//    override val system: ActorSystem
//  ) extends DomainModel {
//    override def name: String = key.name
//
//    private def underlying: DomainModel = registry()( key ).modelCell
//    override def indexBus: IndexBus = underlying.indexBus
//
//    override def get( rootName: String, id: Any ): Option[ActorRef] = underlying.get( rootName, id )
//
//    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
//      underlying.aggregateIndexFor[K, TID, V]( rootName, indexName )
//    }
//
//    override def rootTypes: Set[AggregateRootType] = underlying.rootTypes
//  }


//  final case class BoundedContextlNotRegisteredError private[demesne]( key: Symbol )
//  extends NoSuchElementException( s"no BoundedContext registered for key:[${key.name}]" ) with DemesneError
//}