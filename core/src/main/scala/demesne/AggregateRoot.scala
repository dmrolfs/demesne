package demesne

import akka.actor.{ActorLogging, PoisonPill, ReceiveTimeout}
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}

import scalaz._
import scalaz.Kleisli._
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.identifier.TaggedID
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


object AggregateRoot {
  type Acceptance[S] = PartialFunction[(Any, S), S]
}

abstract class AggregateRoot[S, I]
extends PersistentActor
with EnvelopingActor
with DomainModel.Provider
with AggregateRootType.Provider
with ActorLogging {
  outer: EventPublisher =>

  type StateOperation = KOp[S, S]

  val trace = Trace( s"AggregateRoot", log )

  type Acceptance = AggregateRoot.Acceptance[S]
  def acceptance: Acceptance

  override val persistenceId: String = self.path.toStringWithoutAddress

  type ID = I
  def parseId( idstr: String ): ID
  type TID = TaggedID[ID]

  val aggregateId: ID = parseId( idFromPath() )

  lazy val PathComponents = """^.*\/(([^-]+)-)?(.+)$""".r
  def idFromPath(): String = {
    val PathComponents(_, tag, id) = self.path.toStringWithoutAddress
    id
  }

  def state: S
  def state_=( newState: S ): Unit


  override def around( r: Receive ): Receive = LoggingReceive {
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

  override def preStart(): Unit = {
    super.preStart()
    context setReceiveTimeout rootType.passivation.inactivityTimeout
    rootType.snapshot.schedule( context.system, self )( context.dispatcher )
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case m: ReceiveTimeout => {
        log.warning( "[{}] Passivating per receive-timeout", self.path )
        context.parent ! rootType.passivation.passivationMessage( PoisonPill )
      }

      case m => {
        log.debug( "aggregate root unhandled {}", m )
        super.unhandled( m )
      }
    }
  }
}
