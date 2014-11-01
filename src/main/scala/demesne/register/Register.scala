package demesne.register

import akka.actor.{Props, ActorLogging}
import akka.cluster.Cluster
import akka.contrib.pattern.{DistributedPubSubMediator, ClusterReceptionistExtension, DistributedPubSubExtension}
import akka.persistence.PersistentActor

import scala.reflect.ClassTag


object Register {
//  val IndexTopic = "index"
//  val RegisterSubscriberPath = ""

  def props[K, A]( subscriberPath: String ): Props = Props( new Register[K, A](subscriberPath)  )

  import scala.language.existentials
  sealed trait RegisterMessage
  case class RegisterAggregate( key: Any, id: Any, classifier: Class[_] ) extends RegisterMessage
  case class AggregateRegistered( key: Any, id: Any, classifier: Class[_] ) extends RegisterMessage {
    def mapIdTo[TID]( implicit tag: ClassTag[TID] ): TID = {
      val boxedClass = tag.runtimeClass
      require( boxedClass ne null )
      boxedClass.cast( id ).asInstanceOf[TID]
    }
  }

}

/**
 * Created by damonrolfs on 10/26/14.
 */
class Register[Key, AggregateId]( subscriberPath: String ) extends PersistentActor with ActorLogging {
  import Register._

  val mediator = DistributedPubSubExtension( context.system ).mediator

  override def preStart(): Unit = {
    ClusterReceptionistExtension( context.system ).registerService( self )
  }

  // persistenceId must include cluster role to support multiple masters
  override def persistenceId: String = {
    Cluster( context.system )
      .selfRoles
      .find( _.startsWith( "register-" ) )
      .map( _ + "-master" )
      .getOrElse( "master" )
  }

  type State = Map[Key, AggregateId]
  private var state: State = Map()

  override def receiveRecover: Receive = {
    case AggregateRegistered( key, id, _ ) => {
      val k = key.asInstanceOf[Key]
      val v = id.asInstanceOf[AggregateId]
      state += ( k -> v )
    }
  }

  override def receiveCommand: Receive = {
    case RegisterAggregate( key, id, classifier ) => {
      persistAsync( AggregateRegistered( key, id, classifier ) ) { e =>
        mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
        log info s"aggregate recorded in register: ${key} -> ${id}"
      }
    }
  }
}
