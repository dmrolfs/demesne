package demesne.register

import akka.actor.{ActorRef, Actor, Props, ActorLogging}
import akka.cluster.Cluster
import akka.contrib.pattern.{DistributedPubSubMediator, ClusterReceptionistExtension, DistributedPubSubExtension}
import akka.event.LoggingReceive
import akka.persistence.PersistentActor
import peds.commons.log.Trace

import scala.reflect.ClassTag
import peds.commons.util._


object Register {
//  val IndexTopic = "index"
//  val RegisterSubscriberPath = ""

//  def props[K: ClassTag, A: ClassTag]( subscriberPath: String ): Props = Props( new Register[K, A](subscriberPath)  )
  def props[K: ClassTag, A: ClassTag]( subscriber: ActorRef ): Props = Props( new Register[K, A]( subscriber )  )

  import scala.language.existentials
  sealed trait RegisterMessage
  case class RegisterAggregate( key: Any, id: Any, classifier: Class[_] ) extends RegisterMessage
  case class AggregateRegistered( key: Any, id: Any, classifier: Class[_] ) extends RegisterMessage {
    def mapIdTo[TID]( implicit tag: ClassTag[TID] ): TID = {

      val boxedClass = {
        val c = tag.runtimeClass
        if ( c.isPrimitive ) AggregateRegistered toBoxed c else c
      }
      require( boxedClass ne null )
      boxedClass.cast( id ).asInstanceOf[TID]
    }
  }

  object AggregateRegistered {
    val toBoxed: Map[Class[_], Class[_]] = Map(
      classOf[Boolean] -> classOf[java.lang.Boolean],
      classOf[Byte]    -> classOf[java.lang.Byte],
      classOf[Char]    -> classOf[java.lang.Character],
      classOf[Short]   -> classOf[java.lang.Short],
      classOf[Int]     -> classOf[java.lang.Integer],
      classOf[Long]    -> classOf[java.lang.Long],
      classOf[Float]   -> classOf[java.lang.Float],
      classOf[Double]  -> classOf[java.lang.Double],
      classOf[Unit]    -> classOf[scala.runtime.BoxedUnit]
    )
  }

}

/**
 * Created by damonrolfs on 10/26/14.
 */
//class Register[Key, AggregateId]( subscriberPath: String ) extends PersistentActor with ActorLogging {
class Register[Key: ClassTag, AggregateId: ClassTag]( subscriber: ActorRef ) extends Actor with ActorLogging {
  import Register._

  val trace = Trace( getClass.safeSimpleName, log )
//  val mediator: ActorRef = DistributedPubSubExtension( context.system ).mediator

  override def preStart(): Unit = trace.block( "preStart" ) {
//    ClusterReceptionistExtension( context.system ).registerService( self )
  }

  // persistenceId must include cluster role to support multiple masters
//  override def persistenceId: String = trace.block( "persistenceId" ) {
//    "register"
//    Cluster( context.system )
//      .selfRoles
//      .find( _.startsWith( "register-" ) )
//      .map( _ + "-master" )
//      .getOrElse( "master" )
//  }

  type State = Map[Key, AggregateId]
  private var state: State = Map()

//  override def receiveRecover: Receive = LoggingReceive {
//    case AggregateRegistered( key, id, _ ) => trace.block( s"receiveRecover:AggregateRegistered( $key, $id, _ )" ) {
//      val k = key.asInstanceOf[Key]
//      val v = id.asInstanceOf[AggregateId]
//      state += ( k -> v )
//    }
//  }

//  override def receiveCommand: Receive = LoggingReceive {
  override def receive: Receive = LoggingReceive {
//    case RegisterAggregate( key, id, classifier ) => trace.block( s"RegisterAggregate($key, $id, _)" ) {
//      persistAsync( AggregateRegistered( key, id, classifier ) ) { e =>
//        mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
//        log info s"aggregate recorded in register: ${key} -> ${id}"
//      }
//    }
    case mapping @ RegisterAggregate( key, id, classifier ) => trace.block( s"receive:${mapping}" ) {
      val e = AggregateRegistered( key, id, classifier )
      state += ( key.asInstanceOf[Key] -> id.asInstanceOf[AggregateId] )
      subscriber ! e
//      mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
      log info s"aggregate recorded in register: ${key} -> ${id}"
    }

//    case m => unhandled( m )
  }

  override def unhandled(message: Any): Unit = log error s"REGISTER UNHANDLED ${message}"
}
