package demesne

import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import scalaz._, Scalaz._
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.agent.Agent
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import demesne.factory._
import demesne.register._
import demesne.register.RegisterSupervisor.{ FinderRegistered, RegisterFinder }
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ StartChild, ChildStarted }
import peds.akka.supervision.{ IsolatedDefaultSupervisor, OneForOneStrategyFactory }
import peds.commons.log.Trace



trait DomainModel {
  def name: String
  def system: ActorSystem
  def registerBus: RegisterBus
  def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory )( implicit to: Timeout ): Future[Unit]
  def aggregateOf( rootType: AggregateRootType, id: Any ): ActorRef = aggregateOf( rootType.name, id )
  def aggregateOf( name: String, id: Any ): ActorRef
  def registerFor( rootType: AggregateRootType, name: Symbol ): RegisterEnvelope = registerFor( rootType.name, name )
  def registerFor( rootName: String, registerName: Symbol ): RegisterEnvelope
  def shutdown(): Unit
}

object DomainModel {
  import scala.concurrent.ExecutionContext.global
  val trace = Trace[DomainModel.type]

  def register( name: String )( implicit system: ActorSystem ): V[Future[DomainModel]] = trace.block( s"register($name)($system)" ) {
    implicit val ec = global
    val key = Key( name, system )
    val model = DomainModelImpl( name, system )
    val result = for { _ <- modelRegistry alter { _ + (key -> model) } } yield model
    result.successNel
  }

  def apply( name: String )( implicit system: ActorSystem ): V[DomainModel] = trace.block( s"apply(${name})(${system})" ) {
    val k = Key( name, system )
    trace( s"key=$k" )

    import scalaz.Validation.FlatMap._

    for {
      dm <- checkRegister( Key(name, system) )
      _ <- checkSystem( dm, system )
    } yield dm
  }

  private def checkRegister( key: Key ): V[DomainModel] = {
    modelRegistry().get( key ).map{ _.successNel } getOrElse{ DomainModelNotRegisteredError(key.name, key.system).failureNel }
  }

  private def checkSystem( dm: DomainModel, expected: ActorSystem): V[ActorSystem] = {
    if ( dm.system == expected ) dm.system.successNel
    else ActorSystemMismatchError( dm, expected ).failureNel
  }


  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  final case class Key private[demesne]( name: String, system: ActorSystem )

  private val modelRegistry: Agent[Map[Key, DomainModel]] = Agent( Map.empty[Key, DomainModel] )( global )

  private[demesne] object DomainModelImpl {
    type RootTypeRef = (ActorRef, AggregateRootType)
    type AggregateRegistry = Map[String, RootTypeRef]
  }

  final case class DomainModelImpl private[demesne](
    override val name: String, 
    override val system: ActorSystem
  ) extends DomainModel with LazyLogging {
    import DomainModelImpl._

    // default dispatcher is okay since mutations are limited to bootstrap.
    val aggregateRegistry: Agent[AggregateRegistry] = Agent( Map[String, RootTypeRef]() )( system.dispatcher )

    type FinderSpecLike = FinderSpec[_, _]
    type RegisterAgent = RegisterEnvelope
    type SpecAgents = Map[FinderSpecLike, RegisterAgent]
    val specAgentRegistry: Agent[SpecAgents] = Agent( Map[FinderSpecLike, RegisterAgent]() )( system.dispatcher )

//use this to make register actors per root type
    val repositorySupervisor: ActorRef = system.actorOf(
      Props(
        new IsolatedDefaultSupervisor with OneForOneStrategyFactory {
          override def childStarter(): Unit = { }
        }
      ),
      "Repositories"
    )

    override val registerBus: RegisterBus = new RegisterBus
    val registerSupervisor: ActorRef = system.actorOf( RegisterSupervisor.props( registerBus ), "AggregateRegisters" )

    override def aggregateOf( name: String, id: Any ): ActorRef = trace.block( s"aggregateOf($name, $id)" ) {
      trace( s"""name = $name; system = $system; id = $id; aggregateTypeRegistry = ${aggregateRegistry().mkString("[", ",", "]")} """ )

      aggregateRegistry().get( name ) map { rr =>
        val (aggregateRepository, _) = rr
        aggregateRepository
      } getOrElse {
        throw new IllegalStateException(s"""DomainModel type registry does not have root type:${name}:: registry=${aggregateRegistry().mkString("[",",","]")}""")
      }
    }


    override def registerFor( rootName: String, registerName: Symbol ): RegisterEnvelope = trace.block( s"registerFor($rootName, $registerName)" ) {
      trace( s"""aggregateRegistry = ${aggregateRegistry().mkString("[",",","]")}""")
      trace( s"""specAgentRegistry=${specAgentRegistry().mkString("[",",","]")}""" )

      val result = for {
        (_, rootType) <- aggregateRegistry() get rootName
        spec <- rootType.finders find { _.name == registerName }
        agent <- specAgentRegistry() get spec
      } yield {
        trace( s"""rootName=$rootName; rootType=$rootType""" )
        trace( s"spec = ${spec}" )
        trace( s"agent = ${agent}" )
        agent
      }

      result getOrElse {
        throw new IllegalStateException(s"""DomainModel does not have register for root type:${rootName}:: specAgentRegistry=${specAgentRegistry().mkString("[",",","]")}""")
      }
    }

    override def registerAggregateType(
      rootType: AggregateRootType,
      factory: ActorFactory
    )(
      implicit to: Timeout
    ): Future[Unit] = trace.block( "registerAggregateType" ) {
      implicit val ec = system.dispatcher

      val (registerFinderBudget, registerAgentBudget, startChildSupervisionBudget) = timeoutBudgets( to )

      val result = for {
        reg <- establishRegister( rootType, registerFinderBudget, registerAgentBudget )
        ag <- registerAggregate( rootType, factory, startChildSupervisionBudget )
      } yield ()

      result onFailure {
        case ex => logger error s"failed to establish register for ${rootType}: ${ex}"
      }

      result
    }

    /**
     * Returns the individual timeout budget components for aggregate type registration.
     * @return (Register Finder, Get Agent Register, Start Supervised Child)
     */
    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout, Timeout) = {
      //      implicit val askTimeout: Timeout = 900.millis //todo: move into configuration OR use one-time actor
      val baseline = to.duration - 200.millis
      ( Timeout( baseline / 2 ), Timeout( baseline / 4 ), Timeout( baseline / 4 ) )
    }

    private def registerAggregate(
      rootType: AggregateRootType,
      factory: ActorFactory,
      budget: Timeout
    )(
      implicit ex: ExecutionContext
    ): Future[Unit] = trace.block( s"registerAggregate($rootType,_)" ) {
      import akka.pattern.ask
      trace( s"register timeout budget = $budget" )

      val aggregate = aggregateRegistry alter { r =>
        trace.block(s"[name=${name}, system=${system}] registry send ${rootType}") {
          trace( s"DomainModel.name= $name" )
          if (r.contains(rootType.name)) r
          else {
            val props = EnvelopingAggregateRootRepository.props( this, rootType, factory )
            val entry = for {
              repoStarted <- ask( repositorySupervisor, StartChild(props, rootType.repositoryName) )( budget ).mapTo[ChildStarted]
              repo = repoStarted.child
            } yield ( rootType.name, (repo, rootType) )

            //todo make configuration driven
            //todo is Await necessary?
            val e = Await.result( entry, 3.seconds )
            r + e
          }
        }
      }

      aggregate map { r => () }
    }

    private def establishRegister( 
      rootType: AggregateRootType, 
      registerFinderBudget: Timeout, 
      agentBudget: Timeout
    )( 
      implicit ec: ExecutionContext 
    ): Future[Unit] = trace.block( s"establishRegister($rootType)" ) {
      import akka.pattern.ask
      trace( s"registration timeout budget = $registerFinderBudget" )
      trace( s"agent timeout budget = $agentBudget" )

      val registers = rootType.finders map { s =>
        for {
          registration <- registerSupervisor.ask( RegisterFinder(rootType, s) )( registerFinderBudget ).mapTo[FinderRegistered]  //todo askretry
          agentRef = registration.agentRef
          agentEnvelope <- agentRef.ask( GetRegister )( agentBudget ).mapTo[RegisterEnvelope]  // todo askretry
          ar <- specAgentRegistry alter { m => m + (s -> agentEnvelope) }
        } yield ar
      }

      Future.sequence( registers ) map { r => () }
    }

    override def shutdown(): Unit = system.shutdown()

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }


  final case class DomainModelNotRegisteredError private[demesne]( name: String, system: ActorSystem )
  extends NoSuchElementException( s"no DomainModel registered for name [$name] and system [$system]" ) 
  with DemesneError

  final case class ActorSystemMismatchError private[demesne]( dm: DomainModel, expected: ActorSystem )
  extends IllegalArgumentException( 
    s"requested DomainModel [${dm.name}] exists under another system [${dm.system}] not expected [$expected]" 
  ) with DemesneError
}
