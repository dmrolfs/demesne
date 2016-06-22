package demesne.register

import scala.reflect.ClassTag
import akka.actor.{ ActorLogging, ActorRef, Props }
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSub
import akka.event.LoggingReceive
import akka.persistence.{ PersistentActor, SnapshotOffer }
import peds.commons.log.Trace
import peds.commons.util._


object RegisterAggregate {
  /**
   * Create an Akka Props for the [[RegisterAggregate]] actor corresponding to a specific key-to-identifier index. 
   */
  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterAggregate[K, I]( topic )  )

  /**
   * Create a standard pub/sub topic label for the given aggregate root type name and key type.
   */
  // def topic( rootType: String, keyType: String ): String = s"register/${rootType}/${keyType}"

  import scala.language.existentials

  sealed trait Event

  /**
   * Index key to identfier recorded.
   */
  case class Recorded( key: Any, keyType: Class[_], id: Any, idType: Class[_] ) extends Event {
    def mapIdTo[T]( implicit tag: ClassTag[T] ): T = {
      val boxedClass = {
        val c = tag.runtimeClass
        if ( c.isPrimitive ) {
          AggregateRegistered toBoxed c
        } else {
          require( c isAssignableFrom idType, s"target class[${c}}] incompatible with id type[${idType}]" )
          c
        }
      }
      require( boxedClass ne null, s"id[${id}] boxed class cannot be null" )
      boxedClass.cast( id ).asInstanceOf[T]
    }
  }

  case class Withdrawn( identifier: Any, idType: Class[_] ) extends Event

  case class Revised( oldKey: Any, newKey: Any, keyType: Class[_] ) extends Event


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
 * [[RegisterAggregate]] maintains the logical index for an Aggregate Root. Index keys to identifier values are
 * [[demesne.register.Directive.Record]]ed. Recorded events are published via a distrubuted pub/sub mechanism to a relay who
 * makes sure the index is recorded in a local Register Akka Agent for easier access.
 * Created by damonrolfs on 10/26/14.
 */
class RegisterAggregate[K: ClassTag, I: ClassTag]( topic: String ) extends PersistentActor with ActorLogging {
  import akka.cluster.pubsub.DistributedPubSubMediator.Publish
  import demesne.register.RegisterAggregate._

  val trace = Trace( getClass.safeSimpleName, log )

  /**
   * Distributed pub/sub channel used to deliver news of aggregate root indexing.
   */
  val mediator: ActorRef = DistributedPubSub( context.system ).mediator
  val keyType: Class[_] = implicitly[ClassTag[K]].runtimeClass
  val idType: Class[_] = implicitly[ClassTag[I]].runtimeClass

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

  type State = Map[K, I]
  private var state: State = Map()

  /**
   * Update the state with the new index.
   */
  private def updateState( event: Any ): Unit = trace.block( s"updateState(${event}})" ) {
    event match {
      case e @ Recorded( key: K, _, _, _ ) => {
        val id = e.mapIdTo[I]
        state += ( key -> id )
        log debug s"RegisterAggregate RECORDED: ${key} -> ${id}"
      }

      case Withdrawn( identifier: I, _ ) => {
        val key = state collectFirst { case kv if kv._2 == identifier => kv._1 }

        key match {
          case Some(k) => {
            state -= k
            log info s"RegisterAggregate WITHDRAWN: ${k}"
          }

          case None => log info s"RegisterAggregate could find identifier [$identifier] to withdraw"
        }
      }

      case e @ Revised( oldKey: K, newKey: K, _ ) if state.contains(oldKey) => {
        val id = state( oldKey )
        state += ( newKey -> id )
        state -= oldKey
        log debug s"RegisterAggregate REVISED: ${oldKey} to ${newKey}"
      }
    }
  }

  /**
   * Akka Persistence handler used to rehydrate aggregate from event journal.
   */
  override val receiveRecover: Receive = LoggingReceive {
    case e: Recorded => trace.block( s"receiveRecover:$e" ) { updateState( e ) }
    case e: Withdrawn => trace.block( s"receiveRecover:$e" ) { updateState( e ) }
    case e: Revised => trace.block( s"receiveRecover:$e" ) { updateState( e ) }
    case SnapshotOffer( _, snapshot ) => trace.block( s"receiveRecover:SnapshotOffer(_, ${snapshot})" ) {
      state = snapshot.asInstanceOf[State]
    }
  }

  /**
   * Akka Persistence handler used to receive command when the aggregate actor is active.
   * Record commands are processed asynchronously to update the index with a new logical key to identifier mapping.
   */
  override def receiveCommand: Receive = LoggingReceive {
    // Record commands are processed asynchronously to update the index with a new logical key to identifier mapping.
    case Directive.Record( key: K, id: I ) => trace.block( s"RecordAggregate.receiveCommand:RECORD($key, $id)" ) {
      persistAsync( Recorded( key = key, keyType = keyType, id = id, idType = idType ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case w: Directive.Withdraw[I] => trace.block( s"RecordAggregate.receiveCommand:WITHDRAW(${w.identifier})" ) {
      persistAsync( Withdrawn( identifier = w.identifier, idType = idType ) ) { e => 
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case Directive.Revise( oldKey: K, newKey: K ) => trace.block( s"RecordAggregate.receiveCommand:REVISE($oldKey, $newKey" ) {
      persistAsync( Revised( oldKey = oldKey, newKey = newKey, keyType = keyType ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    // Register actors dependent on the aggregate issue a WaitingForStart message
    case WaitingForStart => {
      log debug s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }

    case "print" => println( s"register state = ${state}" )
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case _: akka.persistence.RecoveryCompleted => ()

      case Directive.Record(k, i) => {
        log.warning( 
          s"REGISTER UNHANDLED ${message} - verify ${topic} AggregateRootType indexes() type parameterization " +
          s"matches [${k.getClass.safeSimpleName}, ${i.getClass.safeSimpleName}]"
        )
      }

      case m => log warning s"REGISTER UNHANDLED ${message}"
    }
  }
}
