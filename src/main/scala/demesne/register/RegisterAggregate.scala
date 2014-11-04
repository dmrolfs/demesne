package demesne.register

import akka.actor.{ActorRef, Actor, Props, ActorLogging}
import akka.cluster.Cluster
import akka.contrib.pattern.{DistributedPubSubMediator, ClusterReceptionistExtension, DistributedPubSubExtension}
import akka.event.LoggingReceive
import akka.persistence.{SnapshotOffer, PersistentActor}
import peds.commons.log.Trace

import scala.reflect.ClassTag
import peds.commons.util._


object RegisterAggregate {
//  val IndexTopic = "index"
//  val RegisterSubscriberPath = ""
  type Register[K, I] = Map[K, I]

//  def props[K: ClassTag, A: ClassTag]( subscriberPath: String ): Props = Props( new Register[K, A](subscriberPath)  )
  def props[K: ClassTag, I: ClassTag]( subscriber: ActorRef ): Props = Props( new RegisterAggregate[K, I]( subscriber )  )

  import scala.language.existentials
  sealed trait Command
  case class RecordAggregate( key: Any, id: Any ) extends Command

  sealed trait Event
  case class AggregateRecorded( key: Any, id: Any ) extends Event {
    def mapIdTo[T]( implicit tag: ClassTag[T] ): T = {

      val boxedClass = {
        val c = tag.runtimeClass
        if ( c.isPrimitive ) AggregateRegistered toBoxed c else c
      }
      require( boxedClass ne null )
      boxedClass.cast( id ).asInstanceOf[T]
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
class RegisterAggregate[K: ClassTag, I: ClassTag]( subscriber: ActorRef ) extends PersistentActor with ActorLogging {
  import RegisterAggregate._

  val trace = Trace( getClass.safeSimpleName, log )
//  val mediator: ActorRef = DistributedPubSubExtension( context.system ).mediator

  override def preStart(): Unit = trace.block( "preStart" ) {
    super.preStart()
//    ClusterReceptionistExtension( context.system ).registerService( self )
  }

  // persistenceId must include cluster role to support multiple masters


  override def persistenceId: String = {
    "register"
//    Cluster( context.system )
//      .selfRoles
//      .find( _.startsWith( "register-" ) )
//      .map( _ + "-master" )
//      .getOrElse( "master" )
  }

  type State = Register[K, I]
  private var state: State = Map()

  private def updateState( event: Any ): Unit = trace.block( s"updateState(${event}})" ) {
    event match {
      case e @ AggregateRecorded( key: K, _ ) => {
        val id = e.mapIdTo[I]
        state += ( key -> id )
        log info s"aggregate recorded in register: ${key} -> ${id}"
      }
    }
  }

  override val receiveRecover: Receive = LoggingReceive {
    case e: AggregateRecorded => trace.block( s"receiveRecover:$e" ) { updateState( e ) }
    case SnapshotOffer( _, snapshot ) => trace.block( s"receiveRecover:SnapshotOffer(_, ${snapshot})" ) {
      state = snapshot.asInstanceOf[Register[K, I]]
    }
  }

//  override def receive: Receive = receiveCommand

  override def receiveCommand: Receive = LoggingReceive {
    case RecordAggregate( key: K, id: I ) => trace.block( s"receiveCommand:RecordAggregate($key, $id)" ) {
      persist( AggregateRecorded( key, id ) ) { e =>
        updateState( e )
        //        mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
        subscriber ! e
      }
    }

//    case RecordAggregate( key, id ) => trace.block( s"receiveCommand:RecordAggregate($key, $id)" ) {
//      persist( AggregateRecorded( key, id ) ) { e =>
//        updateState( e )
//        //        mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
//        subscriber ! e
//      }
//    }

    case "print" => println( s"register state = ${state}" )
//    case RecordAggregate( key: K, id: I ) => trace.block( s"receive:RegisterAggregate($key, $id})" ) {
//      val e = AggregateRecorded( key, id )
//      state += ( key -> id )
//      subscriber ! e
////      mediator ! DistributedPubSubMediator.SendToAll( path = subscriberPath, msg = e, allButSelf = true )
//      log info s"aggregate recorded in register: $key -> $id"
//    }

    case m => unhandled( m )
  }

  override def unhandled(message: Any): Unit = log error s"REGISTER UNHANDLED ${message}"
}
