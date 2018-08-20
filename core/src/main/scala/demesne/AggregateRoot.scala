package demesne

import scala.reflect._
import scala.concurrent.duration.Duration
import scala.util.Try
import akka.actor.{ ActorLogging, ActorPath, ActorRef, ReceiveTimeout }
import akka.cluster.sharding.ShardRegion
import akka.persistence._
import cats.data.Kleisli
import cats.instances.either._
import cats.syntax.either._
import shapeless.the
import omnibus.akka.envelope._
import omnibus.akka.publish.EventPublisher
import omnibus.akka.ActorStack
import omnibus.identifier.{ Id, Identifying }
import omnibus.core.{ ErrorOr, KOp }
import omnibus.core.syntax.clazz._
import demesne.PassivationSpecification.StopAggregateRoot

//////////////////////////////////////
// Vaughn Vernon idea:
// def on( orderPlaced: OrderPlaced ) = {
//   ...
// }
// registerInterestHandler( classOf[OrderPlaced], (message: OrderPlaced) => on(message) )

// enables messages handlers defined in operations. and registered in actor def
// support via subtrait of AggregateRoot?
// support registration with "state" handler (context become state)
//////////////////////////////////////

object AggregateRoot {
  trait Provider extends DomainModel.Provider with AggregateRootType.Provider

  type Acceptance[S] = PartialFunction[( Any, S ), S]

  def aggregateIdFromPath[S: Identifying]( path: ActorPath ): Identifying[S]#TID = {
//    identifying tidFromString java.net.URLDecoder.decode( self.path.name, "utf-8" )
    the[Identifying[S]].fromString( path.toStringWithoutAddress )
  }

  def aggregateIdFromRef[S: Identifying]( aggregateRef: ActorRef ): Identifying[S]#TID = {
    aggregateIdFromPath( aggregateRef.path )
  }

  def persistenceIdFromPath[S: Identifying]( path: ActorPath ): String = {
    aggregateIdFromPath( path ).toString
  }
}

abstract class AggregateRoot[S: Identifying: ClassTag]
    extends PersistentActor
    with ActorStack
    with EnvelopingActor
    with ActorLogging {
  outer: AggregateRoot.Provider with EventPublisher =>

  val StopMessageType: ClassTag[_] = classTag[PassivationSpecification.StopAggregateRoot[ID]]

  context.setReceiveTimeout( outer.rootType.passivation.inactivityTimeout )

  val snapshotCancellable = outer.rootType.snapshot map {
    _.schedule( context.system, self, aggregateId )( context.system.dispatcher )
  }

  def passivate(): Unit = context stop self

  override def postStop(): Unit = {
    log.debug( "[{}] clearing receive inactivity and snapshot timer on actor stop", self.path )
    context.setReceiveTimeout( Duration.Undefined )
    snapshotCancellable foreach { _.cancel() }
    super.postStop()
  }

  override protected def onPersistRejected( cause: Throwable, event: Any, seqNr: Long ): Unit = {
    log.error(
      "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
      event.getClass.getName,
      seqNr,
      persistenceId,
      cause
    )
    throw cause
  }

  type StateOperation = KOp[S, S]

  type Acceptance = AggregateRoot.Acceptance[S]
  def acceptance: Acceptance

  val identifying: Identifying[S] = the[Identifying[S]]
  type ID = identifying.ID
  type TID = identifying.TID
//  lazy val evTID: ClassTag[TID] = classTag[TID]

  def aggregateId: TID = AggregateRoot aggregateIdFromPath self.path

//  def aggregateIdFromPath(): ID = {
//
//  }

  override def persistenceId: String = AggregateRoot persistenceIdFromPath self.path

  // assumes the identifier component of the aggregate path contains only the id and not a tagged id.
//  val PathComponents = """^.*\/(.+)$""".r
//  def persistenceIdFromPath(): String = aggregateIdFromPath().toString

  def state: S
  def state_=( newState: S ): Unit

  def aggregateIdFor( msg: Any ): Option[ShardRegion.EntityId] = {
    if (rootType.aggregateIdFor isDefinedAt msg) Option( rootType.aggregateIdFor( msg )._1 )
    else None
  }

  override def around( r: Receive ): Receive = {
    case _: ReceiveTimeout => {
      log.debug(
        "[{}] id:[{}] sending aggregate stop message per receive timeout",
        self.path,
        aggregateId
      )
      context.parent ! rootType.passivation.passivationMessage(
        PassivationSpecification.StopAggregateRoot[S]( aggregateId )
      )
    }

    case StopMessageType( m ) if aggregateIdFor( m ) == Option( aggregateId.value.toString ) => {
      log.debug(
        "[{}] id:[{}] received stop message and starting passivation and stop",
        self.path,
        aggregateId
      )
      passivate()
    }

    case StopMessageType( m ) if !rootType.aggregateIdFor.isDefinedAt( m ) => {
      log.error( "[{}] root-type cannot find id for stop message[{}]", self.path, m )
    }

    case StopMessageType( m ) => {
      log.error(
        "[{}] unrecognized aggregate stop message with id:[{}] did not match aggregate-id:[{}]",
        self.path,
        aggregateIdFor( m ),
        aggregateId
      )
    }

    case msg => super.around( r orElse handleSaveSnapshot )( msg )
  }

  val handleSaveSnapshot: Receive = {
    case SaveSnapshot( Id( tid ) ) if tid == aggregateId => {
      log.debug( "saving snapshot for pid:[{}] aid:[{}]", persistenceId, aggregateId )
      saveSnapshot( state )
    }

    case success: SaveSnapshotSuccess => onSuccessfulSnapshot( success.metadata )
  }

  def onSuccessfulSnapshot( metadata: SnapshotMetadata ): Unit = {
    log.debug( "aggregate snapshot successfully saved: [{}]", metadata )
  }

  def accept( event: Any ): S = {
    acceptOp( event ) run state match {
      case Right( s ) => s
      case Left( ex ) => throw ex
    }
  }

  def acceptOp( event: Any ): StateOperation = Kleisli[ErrorOr, S, S] { s =>
    Either catchNonFatal {
      val eventState = ( event, s )
      if (acceptance.isDefinedAt( eventState )) {
        state = acceptance( eventState )
        state
      } else {
        log.debug(
          "{} does not accept event {}",
          Option( s ).map { _.getClass.safeSimpleName },
          event.getClass.safeSimpleName
        )
        s
      }
    }
  }

  def publishOp( event: Any ): StateOperation = Kleisli[ErrorOr, S, S] { s =>
    Either catchNonFatal { publish( event ); s }
  }

  def acceptAndPublishOp( event: Any ): StateOperation =
    acceptOp( event ) andThen publishOp( event )

  def acceptAndPublish( event: Any ): S = {
    acceptAndPublishOp( event ) run state match {
      case Right( s ) => s
      case Left( ex ) => throw ex
    }
  }

  def acceptSnapshot( snapshotOffer: SnapshotOffer ): S = {
    the[ClassTag[S]].unapply( snapshotOffer.snapshot ) getOrElse {
      val ex = new IllegalStateException(
        s"snapshot does not match State type:[${the[ClassTag[S]]}]; offer:[${snapshotOffer.snapshot}]"
      )
      log.error( ex, "invalid snapshot offer" )
      throw ex
    }
  }

  override def receiveRecover: Receive = {
    case offer: SnapshotOffer => { state = acceptSnapshot( offer ) }

    case _: RecoveryCompleted => {
      log.debug(
        "RecoveryCompleted from journal for aggregate root actor:[{}] state:[{}]",
        self.path,
        state
      )
    }

    case event => {
      val before = state
      state = accept( event )
      log.debug(
        "[{}] recovered event:[{}] state-before:[{}], state-after",
        self.path,
        event,
        before,
        state
      )
    }
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case StopMessageType( m )
          if rootType.aggregateIdFor.isDefinedAt( m ) && rootType.aggregateIdFor( m ) == aggregateId => {
        log.debug( "[{}] id:[{}] Stopping AggregateRoot after passivation", self.path, aggregateId )
        context stop self
      }

      case m => {
        log.debug(
          "aggregate root[{}] unhandled class[{}]: object:[{}]",
          aggregateId,
          m.getClass.getName,
          m
        )
        super.unhandled( m )
      }
    }
  }
}
