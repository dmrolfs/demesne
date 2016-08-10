package demesne

import akka.actor.{ActorLogging, ActorPath, ActorRef, PoisonPill, ReceiveTimeout}
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.typesafe.scalalogging.LazyLogging

import scalaz._
import scalaz.Kleisli._
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.identifier.{Identifying, TaggedID}
import peds.commons.{KOp, TryV}
import peds.commons.log.Trace
import peds.commons.util._


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


object AggregateRoot extends LazyLogging {
  trait Provider extends DomainModel.Provider with AggregateRootType.Provider

  type Acceptance[S] = PartialFunction[(Any, S), S]

  def aggregateIdFromPath[S, I]( path: ActorPath )( implicit identifying: Identifying[S] ): TaggedID[I] = {
    identifying.tidAs[TaggedID[I]]( identifying.tag( identifying.fromString( path.toStringWithoutAddress ) ) ) match {
      case \/-( tid ) => tid
      case -\/( ex ) => {
        logger.error( s"failed to extract tagged id form path[${path.toStringWithoutAddress}]", ex )
        throw ex
      }
    }
  }

  def aggregateIdFromRef[S: Identifying, I]( aggregateRef: ActorRef ): TaggedID[I] = {
    aggregateIdFromPath[S, I]( aggregateRef.path )
  }
}

abstract class AggregateRoot[S, I]
extends PersistentActor
with AggregateRoot.Provider
with EnvelopingActor
with ActorLogging {
  outer: EventPublisher =>

  context.setReceiveTimeout( rootType.passivation.inactivityTimeout )

  override protected def onPersistRejected( cause: Throwable, event: Any, seqNr: Long ): Unit = {
    log.error(
      "Rejected to persist event type [{}] with sequence number [{}] for persistenceId [{}] due to [{}].",
      event.getClass.getName, seqNr, persistenceId, cause
    )
    throw cause
  }

  type StateOperation = KOp[S, S]

  val trace = Trace( s"AggregateRoot", log )

  type Acceptance = AggregateRoot.Acceptance[S]
  def acceptance: Acceptance

  override val persistenceId: String = self.path.toStringWithoutAddress

  type ID = I
  def parseId( idstr: String ): TID

  type TID = TaggedID[ID]

  lazy val aggregateId: TID = parseId( idFromPath() )

  // assumes the identifier component of the aggregate path contains only the id and not a tagged id.
  lazy val PathComponents = """^.*\/(.+)$""".r
  def idFromPath(): String = {
    val PathComponents(id) = self.path.toStringWithoutAddress
    id
  }

  def state: S
  def state_=( newState: S ): Unit


  override def around( r: Receive ): Receive = {
    case SaveSnapshot => {
      log.debug( "received SaveSnapshot command" )
      saveSnapshot( state )
      super.around( r )( SaveSnapshot )
    }

    case msg => trace.block( "AggregateRoot.around(_)" ) { super.around( r )( msg ) }
  }


  def accept( event: Any ): S = {
    acceptOp(event) run state match {
      case \/-(s) => s
      case -\/(ex) => throw ex
    }
  }

  def acceptOp( event: Any ): StateOperation = kleisli[TryV, S, S] { s =>
    trace.block( s"acceptOp($event, $s)" ) {
      \/.fromTryCatchNonFatal[S] {
        val eventState = (event, s)
        if ( acceptance.isDefinedAt( eventState ) ) {
          val newState = acceptance( eventState )
          log.debug( "newState = {}", newState )
          log.debug( "BEFORE state = {}", state )
          state = newState
          log.debug( "AFTER state = {}", state )
          newState
        } else {
          log.debug( "{} does not accept event {}", Option(s).map{_.getClass.safeSimpleName}, event.getClass.safeSimpleName )
          s
        }
      }
    }
  }

  def publishOp( event: Any ): StateOperation = kleisli[TryV, S, S] { s =>
    trace.block( s"publishOp($event, $s)" ) {
      \/.fromTryCatchNonFatal[S] { 
        publish( event ) 
        s
      }
    }
  }

  def acceptAndPublishOp( event: Any ): StateOperation = acceptOp( event ) andThen publishOp( event )

  def acceptAndPublish( event: Any ): S = {
    acceptAndPublishOp( event ) run state match {
      case \/-(s) => s
      case -\/(ex) => throw ex
    }
  }

  def acceptSnapshot( snapshotOffer: SnapshotOffer ): S = accept( snapshotOffer.snapshot )


  override def receiveRecover: Receive = {
    case offer: SnapshotOffer => { state = acceptSnapshot( offer ) }

    case _: RecoveryCompleted => {
      log.info( "RecoveryCompleted from journal for aggregate root actor:[{}] state:[{}]", self.path, state )
    }

    case event => {
      val before = state
      state = accept( event )
      log.info( "[{}] recovered event:[{}] state-before:[{}], state-after", self.path, event, before, state )
    }
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case m: ReceiveTimeout => {
        log.info( "[{}] id:[{}] Passivating per receive-timeout", self.path, aggregateId )
        context.parent ! rootType.passivation.passivationMessage( PassivationSpecification.StopAggregateRoot[TID](aggregateId) )
      }

      case stop @ PassivationSpecification.StopAggregateRoot(id) if id == aggregateId => {
        log.info( "[{}] id:[{}] Stopping AggregateRoot after passivation", self.path, aggregateId )
        context stop self
      }

      case m => {
        log.debug( "aggregate root[{}] unhandled class[{}]: object:[{}]", aggregateId, m.getClass.getCanonicalName, m )
        super.unhandled( m )
      }
    }
  }
}
