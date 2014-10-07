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
with ActorLogging {
  outer: EventPublisher =>

  val trace = Trace( "AggregateRoot", log )

  override def persistenceId: String = self.path.toStringWithoutAddress

  val meta: AggregateRootType
  var state: S


  override def around( r: Receive ): Receive = LoggingReceive {
    case SaveSnapshot => {
      log info "received SaveSnapshot command"
      saveSnapshot( state )
      super.around( r )( SaveSnapshot )
    }

    case msg => trace.block( "AggregateRoot.around(_)" ) { super.around( r )( msg ) }
  }


  def accept( event: Any ): S = {
    val result = implicitly[AggregateStateSpecification[S]].accept( state, event )
    transition( event )
    result
  }

  def acceptAndPublish( event: Any ): S = {
    val result = accept( event )
    publish( event )
    result
  }

  def acceptSnapshot( snapshotOffer: SnapshotOffer ): S = accept( snapshotOffer.snapshot )


  type Transition = PartialFunction[Any, Unit]
  def transitionFor( state: S ): Transition = peds.commons.util.emptyBehavior[Any, Unit]()

  def transition( event: Any ): Unit = {
    val core = transitionFor( state )
    val t: Transition = ( core andThen infoTransitioning ) orElse infoNotTransitioning

    event match {
      case (e, ctx) if core isDefinedAt e => t( e )
      case e => t( e )
    }
  }

  val infoTransitioning: Transition = {
    case u => log info s"${self.path.name} transitioning for ${u.getClass.safeSimpleName}"
  }

  val infoNotTransitioning: Transition = {
    case ex => log info s"${self.path.name} will not transition state for ${ex.getClass.safeSimpleName}"
  }

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
      case m => super.unhandled( m )
    }
  }
}
