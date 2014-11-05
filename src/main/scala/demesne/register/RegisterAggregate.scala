package demesne.register

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.cluster.Cluster
import akka.contrib.pattern.DistributedPubSubExtension
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, SnapshotOffer}
import peds.commons.log.Trace
import peds.commons.util._

import scala.reflect.ClassTag


object RegisterAggregate {
  type Register[K, I] = Map[K, I]

  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterAggregate[K, I]( topic )  )

  def topic( rootType: String, keyType: String ): String = s"register/${rootType}/${keyType}"

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
    //todo find a better home; i.e., someplace utility like
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
class RegisterAggregate[K: ClassTag, I: ClassTag]( topic: String )
extends PersistentActor
with ActorLogging {
  import akka.contrib.pattern.DistributedPubSubMediator.Publish
  import demesne.register.RegisterAggregate._

  val trace = Trace( getClass.safeSimpleName, log )
  val mediator: ActorRef = DistributedPubSubExtension( context.system ).mediator


  // persistenceId must include cluster role to support multiple masters
  override lazy val persistenceId: String = trace.block( "persistenceId" ) {
    topic +
    "/" +
    Cluster( context.system )
      .selfRoles
      .find( _.startsWith( "register-" ) )
      .map( _ + "-master" )
      .getOrElse( "register-master" )
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

  override def receiveCommand: Receive = LoggingReceive {
    case RecordAggregate( key: K, id: I ) => trace.block( s"receiveCommand:RecordAggregate($key, $id)" ) {
      persistAsync( AggregateRecorded( key, id ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case "print" => println( s"register state = ${state}" )
  }

  override def unhandled( message: Any ): Unit = log warning s"REGISTER UNHANDLED ${message}"
}
