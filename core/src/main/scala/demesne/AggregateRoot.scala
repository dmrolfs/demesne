package demesne

import akka.actor.{ActorLogging, ReceiveTimeout}
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, SnapshotOffer}
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
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

abstract class AggregateRoot[S: AggregateStateSpecification]
extends PersistentActor
with EnvelopingActor
with DomainModel.Provider
with AggregateRootType.Provider
with ActorLogging {
  outer: EventPublisher =>

  val trace = Trace( "AggregateRoot", log )

  override def persistenceId: String = self.path.toStringWithoutAddress

  // def meta: AggregateRootType

  // var state: S
  def state: S
  def state_=( newState: S ): Unit


  override def around( r: Receive ): Receive = LoggingReceive {
    case SaveSnapshot => {
      log debug "received SaveSnapshot command"
      saveSnapshot( state )
      super.around( r )( SaveSnapshot )
    }

    case msg => trace.block( "AggregateRoot.around(_)" ) { super.around( r )( msg ) }
  }


  def accept( event: Any ): S = {
    state = implicitly[AggregateStateSpecification[S]].accept( state, event )
    state
  }

  def acceptAndPublish( event: Any ): S = {
    val result = accept( event )
    publish( event )
    result
  }

  def acceptSnapshot( snapshotOffer: SnapshotOffer ): S = accept( snapshotOffer.snapshot )


  override def receiveRecover: Receive = {
    case offer: SnapshotOffer => { state = acceptSnapshot( offer ) }
    case event => { state = accept( event ) }
  }

  override def preStart(): Unit = {
    super.preStart()
    context setReceiveTimeout meta.passivation.inactivityTimeout
    meta.snapshot.schedule( context.system, self )( context.dispatcher )
  }

  override def unhandled( message: Any ): Unit = {
    message match {
      case m: ReceiveTimeout => context.parent ! meta.passivation.passivationMessage( m )
      case m => {
        log debug s"aggregate root unhandled $m"
        super.unhandled( m )
      }
    }
  }
}
