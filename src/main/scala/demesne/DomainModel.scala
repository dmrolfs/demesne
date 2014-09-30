package demesne

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import demesne.factory._
import peds.commons.log.Trace

import scala.concurrent.Await
import scala.concurrent.duration._


trait DomainModel {
  def name: String
  def system: ActorSystem
  def aggregateOf( rootType: AggregateRootType, id: Any ): AggregateRootRef = aggregateOf( rootType.name, id )
  def aggregateOf( name: String, id: Any ): AggregateRootRef
  def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit
  def shutdown(): Unit
}

object DomainModel {
  val trace = Trace[DomainModel.type]

  trait Provider {
    def model: DomainModel
    def system: ActorSystem
  }

  object Provider{
    def unapply( p: Provider ): Option[(DomainModel, ActorSystem)] = Some( ( p.model, p.system ) )
  }


  def apply( name: String = "domain.model" )( implicit system: ActorSystem ): DomainModel = trace.block( s"(${name}, ${system})" ) {
    val k = Key( name, system )
    modelRegistry.getOrElse(
      k,
      {
        val result = new DomainModelImpl( name )
        modelRegistry += k -> result
        trace( s"domain model registry = ${modelRegistry}" )
        result
      }
    )
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  private[this] case class Key( name: String, system: ActorSystem )

  private[this] var modelRegistry: Map[Key, DomainModel] = Map()

  private object DomainModelImpl {
    type RootTypeRef = (AggregateRootType, ActorRef)
    type AggregateRegistry = Map[String, RootTypeRef]

    object Registry {
      def props: Props = Props[Registry]

      case class RegistryEntry( rootType: AggregateRootType, repo: ActorRef )

      sealed trait RegistryMessage
      case class Register( rootType: AggregateRootType, factory: ActorFactory, system: ActorSystem ) extends RegistryMessage
      case object GetRegistry extends RegistryMessage
      case class RegistryContents( contents: AggregateRegistry )
    }

    // used to protect against concurrent registrations.
    class Registry extends Actor {
      import demesne.DomainModel.DomainModelImpl.Registry._

      var contents: AggregateRegistry = Map()

      override def receive: Receive = {
        case GetRegistry => sender() ! RegistryContents( contents )

        case Register( rootType, factory, system ) if !contents.contains( rootType.name ) => {
          val repoRef = factory( system, rootType )( EnvelopingAggregateRootRepository.props( rootType ) )
          val entry = (rootType, repoRef)
          contents += ( rootType.name -> entry )
        }
      }
    }
  }

  private case class DomainModelImpl(
    override val name: String
  )(
    implicit override val system: ActorSystem
  ) extends DomainModel {
    import demesne.DomainModel.DomainModelImpl._

    val trace = Trace[DomainModelImpl]

    var aggregateTypeRegistry: AggregateRegistry = Map()

    val demesneSystem = ActorSystem( "demesne", ConfigFactory.empty() )
    val registry = demesneSystem actorOf Registry.props

    override def aggregateOf( name: String, id: Any ): AggregateRootRef = trace.block( "aggregateOf" ) {
      trace( s"name = $name" )
      trace( s"id = $id" )
      trace( s"""aggregateTypeRegistry = ${aggregateTypeRegistry.mkString("[", ",", "]")} """ )

      if ( aggregateTypeRegistry contains name ) {
        val (rootType, aggregateRepository) = aggregateTypeRegistry( name )
        AggregateRootRef( rootType, id, aggregateRepository )
      } else {
        throw new IllegalStateException( s"DomainModel type registry does not have root type:${name}" )
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit = trace.block( "registerAggregateType" ) {
      import akka.pattern.ask
      import demesne.DomainModel.DomainModelImpl.Registry.{GetRegistry, Register, RegistryContents}
      implicit val executionContext = system.dispatcher

      trace( s"DomainModel.name= $name" )
      trace( s"DomainModel.system= $system" )
      trace( s"rootType = $rootType" )
      trace( s"factory = ${factory}" )

      registry ! Register( rootType, factory, system )
      val contents = ask( registry, GetRegistry )( 1.second ).mapTo[RegistryContents] map { _.contents }
      aggregateTypeRegistry = Await.result( contents, 2.seconds )

      trace( s"""aggregateTypeRegistry = ${aggregateTypeRegistry.mkString("[", ",", "]")} """ )
      require( aggregateTypeRegistry.contains( rootType.name ), "failed postcondition" )
    }

    override def shutdown(): Unit = {
      system.shutdown()
      demesneSystem.shutdown()
    }

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }
}
