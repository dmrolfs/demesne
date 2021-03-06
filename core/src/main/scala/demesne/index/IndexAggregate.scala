package demesne.index

import scala.reflect._
import akka.actor.{ ActorLogging, ActorRef, Props }
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSub
import akka.event.LoggingReceive
import akka.persistence.{ PersistentActor, SnapshotOffer }
import cats.syntax.either._
import omnibus.core.syntax.clazz._
import omnibus.identifier.{ Id, Identifying }
//import demesne.EventLike

object IndexAggregateProtocol {
  sealed trait Event {
    type TID = Id[_]
    def sourceId: TID
  }

//  //todo does this work? test whether the extractor results in proper Id[E] type
//  object Event {
//    type Aux[E] = Event { type TID = Id[E] }
//
//    def unapply[E]( e: Event.Aux[E] ): Option[Id[E]] = {
//      Some( e.sourceId.asInstanceOf[Id[E]] )
//    }
//  }

  /**
    * Index key to identfier recorded.
    */
  case class Recorded( override val sourceId: Recorded#TID, key: Any, id: Any, value: Any )
      extends Event

  case class Withdrawn( override val sourceId: Withdrawn#TID, key: Option[Any], id: Any )
      extends Event

  case class KeyRevised( override val sourceId: KeyRevised#TID, oldKey: Any, newKey: Any )
      extends Event

  case class ValueRevised(
    override val sourceId: ValueRevised#TID,
    key: Any,
    oldValue: Any,
    newValue: Any
  ) extends Event
}

object IndexAggregate {

  /**
    * Create an Akka Props for the [[IndexAggregate]] actor corresponding to a specific key-to-identifier index.
    */
  def props[K: ClassTag, I: ClassTag, V: ClassTag]( topic: String ): Props =
    Props( new IndexAggregate[K, I, V]( topic ) )

  def mapTo[T]( v: Any )( implicit tag: ClassTag[T] ): T = {
    val boxedClass = {
      val c = tag.runtimeClass
      if (c.isPrimitive) toBoxed( c ) else c
    }
    require( boxedClass ne null )
    boxedClass.cast( v ).asInstanceOf[T]
  }

  private val toBoxed: Map[Class[_], Class[_]] = Map(
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

//todo maybe leverage agent projection?
/**
  * [[IndexAggregate]] maintains the logical index for an Aggregate Root. Index keys to identifier values are
  * [[demesne.index.Directive.Record]]ed. Recorded events are published via a distrubuted pub/sub mechanism to a relay who
  * makes sure the index is recorded in a local Index Akka Agent for easier access.
  * Created by damonrolfs on 10/26/14.
  */
class IndexAggregate[K: ClassTag, I: ClassTag, V: ClassTag]( topic: String )
    extends PersistentActor
    with ActorLogging { outer =>
  import akka.cluster.pubsub.DistributedPubSubMediator.Publish
  import demesne.index.{ IndexAggregateProtocol => P, Directive => D }

  implicit val identifying = {
    val id = IndexIdentifier.make[K, I, V]( topic )

    Identifying.pure[State, IndexIdentifier](
      zeroValueFn = id,
      nextValueFn = () => id,
      valueFromRepFn = _ => id
    )
  }

  val tid: Id[State] = identifying.next
  val KeyType: ClassTag[K] = classTag[K]
  val IdType: ClassTag[I] = classTag[I]
  val ValueType: ClassTag[V] = classTag[V]

  /**
    * Distributed pub/sub channel used to deliver news of aggregate root indexing.
    */
  val mediator: ActorRef = DistributedPubSub( context.system ).mediator

  // persistenceId must include cluster role to support multiple masters
  override lazy val persistenceId: String = {
    val root = Cluster( context.system ).selfRoles
      .find { _.startsWith( "index-" ) }
      .map { _ + "-master" }
      .getOrElse { "index-master" }
    root + "/" + topic
  }

  type State = Map[K, IndexedValue[I, V]]

  private var state: State = Map.empty[K, IndexedValue[I, V]]

  /**
    * Update the state with the new index.
    */
  private def updateState( event: Any ): Unit = {
    log.debug(
      "IndexAggregate[{}]: BEFORE updateState: state:[{}]",
      self.path,
      state.mkString( "\n", "\n", "\n" )
    )

    event match {
      case P.Recorded( _, KeyType( key ), IdType( id ), ValueType( value ) ) => {
        val iValue = IndexedValue[I, V]( id, value )
        log.debug( "IndexAggregate[{}] RECORDED: {} -> {}", self.path, key, iValue )
        state += (key -> iValue)
      }

      case P.Withdrawn( _, Some( KeyType( key ) ), _ ) if state contains key => {
        log.debug( "IndexAggregate[{}] WITHDRAWN via KEY: {}", self.path, key )
        state -= key
      }

      case P.Withdrawn( _, None, IdType( id ) ) if state.exists {
            case ( _, IndexedValue( i, _ ) ) => i == id
          } => {
        log.debug( "IndexAggregate[{}] WITHDRAWN via ID: {}", self.path, id )
        val key = state collectFirst { case ( k, IndexedValue( i, _ ) ) if i == id => k }

        key match {
          case Some( k ) => {
            log.debug( "IndexAggregate removed key:[{}]", k )
            state -= k
          }

          case None => log.debug( "IndexAggregate could not find identifier [{}] to withdraw", id )
        }
      }

      case P.KeyRevised( _, KeyType( oldKey ), KeyType( newKey ) ) if state contains oldKey => {
        val value = state( oldKey )
        state += (newKey -> value)
        state -= oldKey
        log.debug( "IndexAggregate[{}] REVISED: {} to {}", self.path, oldKey, newKey )
      }

      case P.ValueRevised( _, KeyType( key ), ValueType( oldValue ), ValueType( newValue ) )
          if state contains key => {
        val iValue = state( key )
        state += (key -> iValue.copy( value = newValue ))
        log.debug(
          "IndexAggregate[{}] REVISED Key:[{}] VALUE: {} to {}",
          self.path,
          key,
          oldValue,
          newValue
        )
      }

      case e: P.Event => {
        log.warning(
          "IndexAggregate[{}]: asked to update for unrecognized event: [{}]",
          self.path,
          e
        )
      }
    }

    log.debug(
      "IndexedAggregate[{}]: AFTER updateState: state:[{}]",
      self.path,
      state.mkString( "\n", "\n", "\n" )
    )
  }

  /**
    * Akka Persistence handler used to rehydrate aggregate from event journal.
    */
  override val receiveRecover: Receive = LoggingReceive {
    case e: P.Recorded                => updateState( e )
    case e: P.Withdrawn               => updateState( e )
    case e: P.KeyRevised              => updateState( e )
    case e: P.ValueRevised            => updateState( e )
    case SnapshotOffer( _, snapshot ) => state = snapshot.asInstanceOf[State]
  }

  /**
    * Akka Persistence handler used to receive command when the aggregate actor is active.
    * Record commands are processed asynchronously to update the index with a new logical key to identifier mapping.
    */
  override def receiveCommand: Receive = LoggingReceive {
    // Record commands are processed asynchronously to update the index with a new logical key to identifier mapping.
    case D.Record( KeyType( k ), IdType( i ), ValueType( v ) ) => {
      persist( P.Recorded( sourceId = tid, key = k, id = i, value = v ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case D.Withdraw( IdType( i ), Some( KeyType( k ) ) ) if state contains k => {
      persist( P.Withdrawn( sourceId = tid, key = Option( k ), id = i ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case D.Withdraw( IdType( evtId ), None ) if state.exists {
          case ( _, IndexedValue( id, _ ) ) => id == evtId
        } => {
      persist( P.Withdrawn( sourceId = tid, key = None, id = evtId ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case D.ReviseKey( KeyType( oldKey ), KeyType( newKey ) ) if state contains oldKey => {
      persist( P.KeyRevised( sourceId = tid, oldKey = oldKey, newKey = newKey ) ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case D.ReviseValue( KeyType( key ), ValueType( oldValue ), ValueType( newValue ) )
        if state contains key => {
      persist(
        P.ValueRevised( sourceId = tid, key = key, oldValue = oldValue, newValue = newValue )
      ) { e =>
        updateState( e )
        mediator ! Publish( topic = topic, msg = e )
      }
    }

    case D.ReviseValue( KeyType( key ), ValueType( _ ), ValueType( _ ) ) => {
      log.warning(
        "IndexAggregate[{}]: UNHANDLED ReviseValue missing key: key=[{}] state=[{}]",
        self.path,
        ( key, KeyType.runtimeClass ),
        state.mkString( "\n", "\n", "\n" )
      )
    }

    case av @ D.AlterValue( KeyType( key ) ) if state contains key => {
      val event = for {
        oldIndexedValue <- Either.fromOption(
          state.get( key ),
          new java.util.NoSuchElementException(
            s"IndexAggregate does not contain a state entry for key:[${key}]"
          )
        )

        newValue <- Either catchNonFatal { av.alter( oldIndexedValue.value ).asInstanceOf[V] }
      } yield
        P.ValueRevised(
          sourceId = tid,
          key = key,
          oldValue = oldIndexedValue.value,
          newValue = newValue
        )

      event match {
        case Right( evt ) => {
          persist( evt ) { e =>
            updateState( e )
            mediator ! Publish( topic = topic, msg = e )
          }
        }

        case Left( ex ) =>
          log.error( ex, "IndexAggregate[{}]: key:[{}] alteration failed", self.path, key )
      }
    }

    // Index actors dependent on the aggregate issue a WaitForStart message
    case WaitingForStart => {
      log.debug( "recd WaitForStart: sending Started to {}", sender() )
      sender() ! Started
    }

    case D.Ignore => {}
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case _: akka.persistence.RecoveryCompleted => ()

      case D.Withdraw( id, k ) => {
        log.warning(
          s"IndexAggregate[${self.path}] UNHANDLED: [${message}] " +
          s"id:[${id}] type:[${IdType.runtimeClass.safeSimpleName}] " +
          s"key:[${k.toString}] key-class:[${k.getClass.safeSimpleName}] " +
          s"state:[${state}]"
        )
      }

      case D.Record( k, i, v ) => {
        log.warning(
          s"topic:[${topic}] + tid:[${tid}] ~> actor:[${self.path}] UNHANDLED [${message}] - " +
          "verify AggregateRootType.indexes types match Record: key-types:[{}] id-types:[{}] value-types[{}]",
          ( KeyType.runtimeClass, k.getClass ),
          ( IdType.runtimeClass, i.getClass ),
          ( ValueType, v.getClass )
        )
      }

      case Directive.ReviseKey( oldKey, newKey ) => {
        log.warning(
          "IndexAggregate[{}] UNHANDLED KEY REVISION [{}] - " +
          s"verify AggregateRootType indexes() type parameterization old:[${oldKey.toString}] new:[${newKey.toString}] :" +
          "[{}] identifier:[{}]",
          ( topic, self.path ),
          message,
          tid
        )
      }

      case Directive.ReviseValue( _, _, _ ) => {
        log.warning(
          "IndexAggregate[{}] UNHANDLED VALUE REVISION:[{}] - " +
          "verify AggregateRootType indexes type parameterization identifier:[{}]",
          ( topic, self.path ),
          message,
          tid
        )
      }

      case _ =>
        log.warning(
          "IndexAggregate[{}] identifier:[{}] UNHANDLED message:[{}]",
          self.path,
          tid,
          message
        )
    }
  }
}
