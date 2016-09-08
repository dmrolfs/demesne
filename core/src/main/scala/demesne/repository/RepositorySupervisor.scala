package demesne.repository

import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import demesne.repository.StartProtocol.{Started, WaitForStart}
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import demesne.{AggregateRootType, DomainModel}
import demesne.repository.{StartProtocol => SP}
import peds.commons.log.Trace


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
  case object Loading extends RepositoryStartupState
  case object WaitingToInitialize extends RepositoryStartupState
  case object Initializing extends RepositoryStartupState

  final case class StartingRepository private[RepositorySupervisor](
    name: String,
    state: RepositoryStartupState,
    repository: ActorRef,
    initiators: Set[ActorRef],
    dependencies: Set[Symbol] = Set.empty[Symbol]
  ) extends Equals {
    override def toString: String = {
      s"""StartingRepository([${name}:${state}] dependencies:[${dependencies.map{ _.name }.mkString(", ")}] """ +
      s"""initiators:[${initiators.map{ _.path.name }.mkString(", ")}])"""
    }

    def isSatisfiedBy( resources: Map[Symbol, Any] ): Boolean = {
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

  final case class ModelStartupState private[RepositorySupervisor](
    startingRepositories: Set[StartingRepository] = Set.empty[StartingRepository],
    availableResources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    waiting: Set[ActorRef] = Set.empty[ActorRef]
  ) {
    override def toString: String = {
      s"""ModelStartupState(starting:[${startingRepositories.mkString(", ")}] """ +
      s"""resources:[${availableResources.mkString(", ")}] waiting:[${waiting.map{ _.path.name }.mkString(", ")}])"""
    }

    private val trace = Trace[ModelStartupState]

    def repositoriesInState( state: RepositoryStartupState ): Set[StartingRepository] = trace.block(s"repositoriesInState($state)") {
      startingRepositories filter { _.state == state }
    }

    def startingStateFor( name: String ): Option[StartingRepository] = trace.block(s"startingStateFor($name)") { startingRepositories find { _.name == name } }

    def withLoading( name: String, repository: ActorRef, initiator: ActorRef ): ModelStartupState = trace.block("withLoading") {
      val newStarting = StartingRepository( name, state = Loading, repository = repository, initiators = Set(initiator) )
      this.copy( startingRepositories = startingRepositories + newStarting )
    }

    def withRepositoryState( repositoryState: StartingRepository ): ModelStartupState = trace.block(s"withRepositoryState(${repositoryState})") {
      val without = startingRepositories - repositoryState
      this.copy( startingRepositories = without + repositoryState )
    }

    def withWaiting( ref: ActorRef ): ModelStartupState = trace.block("withWaiting") {
      if ( startingRepositories.nonEmpty ) this.copy( waiting = waiting + ref )
      else {
        ref ! Started
        this
      }
    }

    def addResources( resources: Map[Symbol, Any] ): ModelStartupState = trace.block(s"addResources($resources)") {
      this.copy( availableResources = availableResources ++ resources )
    }

    def without( started: StartingRepository ): ModelStartupState = trace.block("without") {
      val newState = this.copy( startingRepositories = startingRepositories - started )
      if ( newState.startingRepositories.isEmpty ) newState.drainWaiting() else newState
    }

    def drainWaiting(): ModelStartupState = trace.block("drainWaiting") {
      logger.debug( "notifying waiting:[{}] that RepositorySupervisor Started", waiting.map{_.path.name}.mkString(", ") )
      waiting foreach { _ ! Started }
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
) extends IsolatedDefaultSupervisor with OneForOneStrategyFactory {
  import RepositorySupervisor._

  override def childStarter(): Unit = {
    rootTypes foreach { rt => self ! StartChild( rt.repositoryProps( model ), rt.repositoryName ) }
  }

  override val receive: Receive = LoggingReceive { start( ModelStartupState() ) }

  def start( implicit state: ModelStartupState ): Receive = {
    case WaitForStart => context become LoggingReceive { start( state withWaiting sender() ) }

    case StartChild( props, name ) if state.startingRepositories.exists( _.name == name ) => {
      log.debug( "repository for [{}] is initializing", name )
      state.startingRepositories
      .find { _.name == name }
      .foreach { starting =>
        val newStarting = starting.copy( initiators = starting.initiators + sender() )
        context become LoggingReceive { start( state withRepositoryState newStarting ) }
      }
    }

    case StartChild( props, name ) if context.child( name ).isDefined => {
      log.debug( "repository found: [{}]", name )
      context.child( name ) foreach { sender() ! ChildStarted( _ ) }
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
      val waitingState = {
        state
        .addResources( resources )
        .withRepositoryState( starting.copy(state = WaitingToInitialize, dependencies = dependencies) )
      }

      val newState = dispatchAllInitializingResources( waitingState )
      context become LoggingReceive { start(newState) }
    }

    case SP.Started => {
      val started = startingStateFor( sender() )
      log.debug(
        "repository [{}] initialized and started - notifying initiators:[{}]",
        started.name,
        started.initiators.map{ _.path }.mkString(", ")
      )
      started.initiators foreach { _ ! ChildStarted( started.repository ) }
      context become LoggingReceive { start( state without started ) }
    }

    case s: ChildStarted if sender() == self => log.debug( "aggregate repository [{}] started at [{}]", s.name, s.child )
  }

  def startingStateFor( sender: ActorRef )( implicit state: ModelStartupState ): StartingRepository = {
    state
    .startingStateFor( sender.path.name )
    .getOrElse { throw new IllegalStateException( s"no starting state for sender:[${sender.path}]" ) }
  }

  def dispatchAllInitializingResources( implicit state: ModelStartupState ): ModelStartupState = {
    val initializing = {
      for {
        waiting <- state repositoriesInState WaitingToInitialize if waiting isSatisfiedBy state.availableResources
        resources = collectResourcesFor( waiting )
      } yield {
        waiting.repository ! SP.Initialize( resources )
        waiting.copy( state = Initializing )
      }
    }

    initializing.foldLeft( state ) { _ withRepositoryState _ }
  }

  def collectResourcesFor( repository: StartingRepository )( implicit state: ModelStartupState ): Map[Symbol, Any] = {
    userResources ++ repository.collectDependenciesFrom( state.availableResources )
  }
}
