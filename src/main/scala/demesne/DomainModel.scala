package demesne

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.agent.Agent
import akka.util.Timeout
import demesne.factory._
import demesne.register.{RegisterBus, RegisterSupervisor}
import demesne.register.RegisterSupervisor.RegisterFinder
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ StartChild, ChildStarted }
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._


trait DomainModel {
  def name: String
  def system: ActorSystem
  def aggregateOf( rootType: AggregateRootType, id: Any ): ActorRef = aggregateOf( rootType.name, id )
  def aggregateOf( name: String, id: Any ): ActorRef
  def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Future[Unit]
  def registerBus: RegisterBus
  def shutdown(): Unit
}

object DomainModel {
  val trace = Trace[DomainModel.type]

  def register( name: String )( implicit system: ActorSystem ): DomainModel = trace.block( s"register($name)($system)" ) {
    val key = Key( name, system )
    val model = new DomainModelImpl( name )
    val alteration = modelRegistry alter { _ + (key -> model) }
    Await.ready( alteration, 200.millis )
    model
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

  import scala.concurrent.ExecutionContext.global
  private val modelRegistry: Agent[Map[Key, DomainModel]] = Agent( Map[Key, DomainModel]() )( global )

  private object DomainModelImpl {
    type RootTypeRef = (ActorRef, AggregateRootType)
    type AggregateRegistry = Map[String, RootTypeRef]
  }

  private case class DomainModelImpl(
    override val name: String
  )(
    implicit override val system: ActorSystem
  ) extends DomainModel {
    import demesne.DomainModel.DomainModelImpl._

    val trace = Trace[DomainModelImpl]

    // default dispatcher is okay since mutations are limited to bootstrap.
    val registry: Agent[AggregateRegistry] = Agent( Map[String, RootTypeRef]() )( system.dispatcher )
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
      trace( s"""name = $name; system = $system; id = $id; aggregateTypeRegistry = ${registry().mkString("[", ",", "]")} """ )

      registry().get( name ) map { rr =>
        val (aggregateRepository, _) = rr
        aggregateRepository
      } getOrElse {
        throw new IllegalStateException(s"""DomainModel type registry does not have root type:${name}:: registry=${registry().mkString("[",",","]")}""")
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Future[Unit] = trace.block( "registerAggregateType" ) {
      import akka.pattern.ask
      implicit val ec = system.dispatcher
      implicit val askTimeout: Timeout = 3.seconds //todo: move into configuration

      val registers = rootType.finders map { s => registerSupervisor ? RegisterFinder( rootType, s) }

      val aggregate = registry alter { r =>
        trace.block(s"[name=${name}, system=${system}] registry send ${rootType}") {
          trace( s"DomainModel.name= $name" )
          if (r.contains(rootType.name)) r
          else {
            val props = EnvelopingAggregateRootRepository.props( this, rootType, factory )
            val entry = for {
              repoStarted <- ask( repositorySupervisor, StartChild(props, rootType.repositoryName) )( 3.seconds ).mapTo[ChildStarted]
              repo = repoStarted.child
            } yield ( rootType.name, (repo, rootType) )

            //todo make configuration driven
            //todo is Await necessary?
            val e = Await.result( entry, 3.seconds )
            r + e
          }
        }
      }

      Future.sequence( registers :+ aggregate ) map { r => {} }
    }

    override def shutdown(): Unit = system.shutdown()

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }
}
