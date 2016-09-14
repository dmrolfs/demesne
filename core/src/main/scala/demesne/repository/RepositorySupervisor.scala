package demesne.repository

import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace
import demesne.{AggregateRootType, DomainModel}
import demesne.repository.{StartProtocol => SP}


/**
  * Created by rolfsd on 8/20/16.
  */
object RepositorySupervisor extends LazyLogging {
  def props(
    model: DomainModel,
    rootTypes: Set[AggregateRootType],
    userResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    configuration: Config = ConfigFactory.empty()
  ): Props = {
    Props( new RepositorySupervisor( model, rootTypes, userResources, configuration ) )
  }


  sealed trait RepositoryStartupState
  case object Quiscent extends RepositoryStartupState
  case object Loading extends RepositoryStartupState
  case object WaitingToInitialize extends RepositoryStartupState
  case object Initializing extends RepositoryStartupState
  case object Started extends RepositoryStartupState


  final case class StartingRepository private[RepositorySupervisor](
    name: String,
    state: RepositoryStartupState,
    repository: Option[ActorRef] = None,
    initiators: Set[ActorRef] = Set.empty[ActorRef],
    dependencies: Set[Symbol] = Set.empty[Symbol]
  ) extends Equals {
    private val trace = Trace[StartingRepository]

    override def toString: String = {
      s"""StartingRepository([${name}:${state}] dependencies:[${dependencies.map{ _.name }.mkString(", ")}] """ +
      s"""initiators:[${initiators.map{ _.path.name }.mkString(", ")}])"""
    }

    def isSatisfiedBy( resources: Map[Symbol, Any] ): Boolean = trace.briefBlock( s"""[${name}] isSatisfiedBy [${resources.keySet.mkString(", ")}] """ ){
      val met = dependencies & resources.keySet
      dependencies == met
    }

    def collectDependenciesFrom( resources: Map[Symbol, Any] ): Map[Symbol, Any] = {
      resources filter { case (k, _) => dependencies contains k }
    }

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[StartingRepository]

    override def hashCode: Int = 41 + name.##

    override def equals( rhs: Any ): Boolean = {
      rhs match {
        case that: StartingRepository => {
          if ( this eq that ) true
          else {
            ( that.## == this.## ) &&
            ( that canEqual this ) &&
            ( this.name == that.name )
          }
        }

        case _ => false
      }
    }
  }


  object ModelStartupState {
    private[repository] def apply(
      rootTypes: Set[AggregateRootType],
      availableResources: Map[Symbol, Any]
    ): ModelStartupState = {
      val starting = rootTypes map { rt => StartingRepository( name = rt.repositoryName, state = Quiscent ) }
      ModelStartupState( starting = starting, availableResources = availableResources )
    }
  }

  final case class ModelStartupState private[RepositorySupervisor](
    starting: Set[StartingRepository] = Set.empty[StartingRepository],
    started: Set[StartingRepository] = Set.empty[StartingRepository],
    availableResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    waiting: Set[ActorRef] = Set.empty[ActorRef]
  ) {
    override def toString: String = {
      s"""ModelStartupState(started:[${started.mkString(", ")}] starting:[${starting.mkString(", ")}] """ +
      s"""resources:[${availableResources.mkString(", ")}] waiting:[${waiting.map{ _.path.name }.mkString(", ")}])"""
    }

    private val trace = Trace[ModelStartupState]

    def repositoriesInState( state: RepositoryStartupState ): Set[StartingRepository] = trace.briefBlock(s"repositoriesInState($state)") {
      starting filter { _.state == state }
    }

    def startingStateFor( name: String ): Option[StartingRepository] = trace.block(s"startingStateFor($name)") { starting find { _.name == name } }

    def withLoading( name: String, repository: ActorRef, initiator: ActorRef ): ModelStartupState = trace.block("withLoading") {
      val newStarting = StartingRepository( name, state = Loading, repository = Option(repository), initiators = Set(initiator) )
      withStartingRepositoryState( newStarting )
    }

    def withStartingRepositoryState( repositoryState: StartingRepository ): ModelStartupState = trace.block( s"withStartingRepositoryState(${repositoryState})" ) {
      logger.debug( "BEFORE new repositoryState:[{}]", repositoryState )
      logger.debug( "BEFORE starting:[{}]", starting.mkString(", ") )
      val without = starting - repositoryState
      logger.debug( "AFTER without:[{}]", without.mkString(", ") )
      this.copy( starting = without + repositoryState )
    }

    def withWaiting( ref: ActorRef ): ModelStartupState = trace.block("withWaiting") {
      if ( starting.nonEmpty ) {
        logger.debug( "Stashing waiting:[{}] repositories are starting:[{}]", ref.path.name, starting.map{ _.name }.mkString(", ") )
        this.copy( waiting = waiting + ref )
      } else {
        logger.debug( "Reply with Started:[{}] repositories HAVE STARTED:[{}]", ref.path.name, started.map{ _.name }.mkString(", ") )
        ref ! SP.Started
        this
      }
    }

    def addResources( resources: Map[Symbol, Any] ): ModelStartupState = trace.block(s"addResources($resources)") {
      this.copy( availableResources = availableResources ++ resources )
    }

    def without( repo: StartingRepository ): ModelStartupState = trace.block("without") {
      val newState = this.copy( started = started + repo, starting = starting - repo )
      if ( newState.starting.isEmpty ) newState.drainWaiting() else newState
    }

    def drainWaiting(): ModelStartupState = trace.block("drainWaiting") {
      logger.debug( "notifying waiting:[{}] that RepositorySupervisor Started", waiting.map{_.path.name}.mkString(", ") )
      waiting foreach { _ ! SP.Started }
      this.copy( waiting = Set.empty[ActorRef])
    }
  }


  final case class DuplicationResourceError private[RepositorySupervisor](
    rootType: AggregateRootType,
    duplicates: Set[Symbol]
  ) extends IllegalStateException(
    s"""aggregate root type [${rootType.name}] sent duplicate resources:[${duplicates.mkString(", ")}], """ +
    "which were removed before adding to available"
  )
}


class RepositorySupervisor(
  model: DomainModel,
  rootTypes: Set[AggregateRootType],
  userResources: Map[Symbol, Any],
  configuration: Config
) extends IsolatedDefaultSupervisor with OneForOneStrategyFactory with LazyLogging {
  import RepositorySupervisor._

  override def childStarter(): Unit = {
    rootTypes foreach { rt => self ! StartChild( rt.repositoryProps( model ), rt.repositoryName ) }
  }

  override val receive: Receive = LoggingReceive { start( ModelStartupState(rootTypes, userResources) ) }

  def start( implicit state: ModelStartupState ): Receive = {
    case SP.WaitForStart => {
      log.debug( "WaitForStart received waiting on [{}]", state )
      context become LoggingReceive { start( state withWaiting sender() ) }
    }

    case StartChild( props, name ) if state.started.exists( s => s.name == name ) && context.child( name ).isDefined => {
      log.debug( "repository found: [{}]", name )
      context.child( name ) foreach { sender() ! ChildStarted( _ ) }
    }

    case StartChild( props, name ) if state.starting.exists( s => s.state != Quiscent && s.name == name ) => {
      log.debug( "repository for [{}] is initializing", name )
      state.starting
      .find { _.name == name }
      .foreach { starting =>
        val newStarting = starting.copy( initiators = starting.initiators + sender() )
        context become LoggingReceive { start( state withStartingRepositoryState newStarting ) }
      }
    }

    case StartChild( props, name ) => {
      log.debug( "creating repository: [{}]", name )
      val repo = context.actorOf( props, name )
      log.debug( "for [{}]: loading repository: [{}]", name, repo )
      repo ! SP.Load
      context become LoggingReceive { start( state.withLoading(name, repository = repo, initiator = sender()) ) }
    }

    case m @ SP.Loaded( rootType, resources, dependencies ) => {
      val starting = startingStateFor( sender() )
      log.debug( "TEST: LOADED BEFORE state:[{}]", state )
      val waitingState = {
        state
        .addResources( resources )
        .withStartingRepositoryState( starting.copy( state = WaitingToInitialize, dependencies = dependencies ) )
      }
      log.debug( "TEST: LOADED AFTER waiting state:[{}]", waitingState )

      val newState = dispatchAllInitializingResources( waitingState )
      log.debug( "TEST: LOADED AFTER newState:[{}]", waitingState )
      context become LoggingReceive { start(newState) }
    }

    case SP.Started => {
      val started = startingStateFor( sender() ).copy( state = Started )
      started.repository foreach { repo =>
        log.debug(
          "repository [{}] initialized and started - notifying initiators:[{}]",
          started.name,
          started.initiators.map{ _.path }.mkString(", ")
        )

        started.initiators foreach { _ ! ChildStarted(repo) }
        context become LoggingReceive { start( state without started ) }
      }
    }

    case s: ChildStarted if sender() == self => log.debug( "aggregate repository [{}] started at [{}]", s.name, s.child )

    case SP.GetStatus => {
      val states = ( state.starting ++ state.started ) map { s => (s.name, s.state) }
      sender() ! SP.StartStatus( Map(states.toSeq:_*) )
    }
  }

  def startingStateFor( sender: ActorRef )( implicit state: ModelStartupState ): StartingRepository = {
    state
    .startingStateFor( sender.path.name )
    .getOrElse { throw new IllegalStateException( s"no starting state for sender:[${sender.path}]" ) }
  }

  def dispatchAllInitializingResources( implicit state: ModelStartupState ): ModelStartupState = {
    log.debug( "dispatching with available resources:[{}]", state.availableResources.keySet.mkString(", ") )
    val initializing = {
      for {
        waiting <- state repositoriesInState WaitingToInitialize
        if waiting.repository.isDefined
        _ = log.debug( "waiting repository dependencies:[{}]", waiting.dependencies.mkString(", ") )
        if waiting.isSatisfiedBy( state.availableResources )
      } yield {
        waiting.repository map { repo =>
          val resources = collectResourcesFor( waiting )
          log.debug(
            "[{}] dependencies met - dispatching to repository resources:[{}]",
            waiting.name,
            resources.keySet.mkString(", ")
          )

          repo ! SP.Initialize( resources )
          waiting.copy( state = Initializing )
        }
      }
    }

    log.debug( "TEST: dispatched to Initializing repositories: [{}]", initializing.flatten.map{ _.name }.mkString(", ") )
    initializing.flatten.foldLeft( state ) { _ withStartingRepositoryState _ }
  }

  def collectResourcesFor( repository: StartingRepository )( implicit state: ModelStartupState ): Map[Symbol, Any] = {
    log.debug(
      "collecting resources for:[{}] dependencies:[{}] available:[{}]",
      repository.name,
      repository.dependencies.mkString(", "),
      userResources.keySet.mkString(", ")
    )

    val collected = userResources ++ repository.collectDependenciesFrom( state.availableResources )
    log.debug( "collected resources: [{}]", collected.mkString(", ") )
    collected
  }
}
