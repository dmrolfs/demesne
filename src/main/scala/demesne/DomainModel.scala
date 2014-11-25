package demesne

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.agent.Agent
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import demesne.factory._
import demesne.register._
import demesne.register.RegisterSupervisor.{FinderRegistered, RegisterFinder}
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ StartChild, ChildStarted }
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


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

  def register( name: String )( implicit system: ActorSystem ): Future[DomainModel] = trace.block( s"register($name)($system)" ) {
    implicit val ec = global
    val key = Key( name, system )
    val model = new DomainModelImpl( name )
    modelRegistry alter { _ + (key -> model) } map { _ => model }
  }

  def apply( name: String )( implicit system: ActorSystem ): DomainModel = trace.block( s"apply(${name})(${system})" ) {
    val k = Key( name, system )
    trace( s"key=$k" )

    val result = modelRegistry().getOrElse(
      k,
      throw new NoSuchElementException( s"no DomainModel registered for name=$name and system=$system" )
    )

    require(
      result.system == system,
      s"requested DomainModel, $name, exists under system=${result.system} not expected=${system}"
    )

    result
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  private case class Key( name: String, system: ActorSystem )

  private val modelRegistry: Agent[Map[Key, DomainModel]] = Agent( Map[Key, DomainModel]() )( global )

  private[demesne] object DomainModelImpl {
    type RootTypeRef = (ActorRef, AggregateRootType)
    type AggregateRegistry = Map[String, RootTypeRef]
  }

  private[demesne] case class DomainModelImpl(
    override val name: String
  )(
    implicit override val system: ActorSystem
  ) extends DomainModel with LazyLogging {
    import demesne.DomainModel.DomainModelImpl._

    val trace = Trace[DomainModelImpl]

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
      trace( s"""rootName=$rootName; registerName=$registerName => specAgentRegistry=${specAgentRegistry().mkString("[",",","]")}""" )

      val result = for {
        (_, rootType) <- aggregateRegistry() get rootName
        spec <- rootType.finders find { _.name == registerName }
        agent <- specAgentRegistry() get spec
      } yield agent

      result getOrElse {
        throw new IllegalStateException(s"""DomainModel does not have register for root type:${rootName}:: specAgentRegistry=${specAgentRegistry().mkString("[",",","]")}""")
      }
    }

    def timeoutBudgets( to: Timeout ): (Timeout, Timeout, Timeout) = {
      //      implicit val askTimeout: Timeout = 900.millis //todo: move into configuration OR use one-time actor
      val baseline = to.duration - 200.millis
      ( Timeout( baseline / 2 ), Timeout( baseline / 4 ), Timeout( baseline / 4 ) )
    }

    def establishRegister( rootType: AggregateRootType )( implicit ec: ExecutionContext, to: Timeout ): Future[Unit] = trace.block( s"establishRegister($rootType)" ) {
      import akka.pattern.ask
      val (registrationBudget, agentBudget, _) = timeoutBudgets( to )
      trace( s"registration timeout budget = $registrationBudget" )
      trace( s"agent timeout budget = $agentBudget" )

      val registers = rootType.finders map { s =>
        for {
          registration <- registerSupervisor.ask(RegisterFinder(rootType, s))(registrationBudget).mapTo[FinderRegistered]  //todo askretry
          agentRef = registration.agentRef
          agentEnvelope <- agentRef.ask(GetRegister)( agentBudget ).mapTo[RegisterEnvelope]  // todo askretry
          ar <- specAgentRegistry alter { m => m + (s -> agentEnvelope)}
        } yield ar
      }

      Future.sequence( registers ) map { r => () }
    }

    def registerAggregate(
      rootType: AggregateRootType,
      factory: ActorFactory
    )(
      implicit ex: ExecutionContext,
      to: Timeout
    ): Future[Unit] = trace.block( s"registerAggregate($rootType,_)" ) {
      import akka.pattern.ask
      val (_, _, registerBudget) = timeoutBudgets( to )
      trace( s"register timeout budget = $registerBudget" )

      val aggregate = aggregateRegistry alter { r =>
        trace.block(s"[name=${name}, system=${system}] registry send ${rootType}") {
          trace( s"DomainModel.name= $name" )
          if (r.contains(rootType.name)) r
          else {
            val props = EnvelopingAggregateRootRepository.props( this, rootType, factory )
            val entry = for {
              repoStarted <- ask( repositorySupervisor, StartChild(props, rootType.repositoryName) )( registerBudget ).mapTo[ChildStarted]
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

    override def registerAggregateType(
      rootType: AggregateRootType,
      factory: ActorFactory
    )(
      implicit to: Timeout
    ): Future[Unit] = trace.block( "registerAggregateType" ) {
      implicit val ec = system.dispatcher

      val result = for {
        reg <- establishRegister( rootType )
        ag <- registerAggregate( rootType, factory )
      } yield ()

      result onFailure {
        case ex => logger error s"failed to establish register for ${rootType}: ${ex}"
      }

      result
    }

    override def shutdown(): Unit = system.shutdown()

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }
}
