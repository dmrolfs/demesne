package demesne

import scala.reflect.ClassTag
import akka.actor.{ Actor, ActorLogging, ReceiveTimeout }
import akka.event.LoggingReceive
import akka.persistence.{ PersistentActor, SnapshotOffer }
import shapeless._
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.log.Trace
import peds.commons.util._
import com.typesafe.scalalogging.StrictLogging


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

abstract class AggregateRoot[S: AggregateStateSpecification] extends PersistentActor with EnvelopingActor with ActorLogging {
  outer: EventPublisher =>

  val trace = Trace( "AggregateRoot", log )

  override def persistenceId: String = self.path.toStringWithoutAddress

  val meta: AggregateRootType
  var state: S

  type Transition = AggregateRoot.Transition
  def transitionFor( state: S ): Transition = peds.commons.util.emptyBehavior[Any, Unit]

  override def around( r: Receive ): Receive = LoggingReceive {
    case SaveSnapshot => {
      log info "received SaveSnapshot command"
      saveSnapshot( state )
      super.around( r )( SaveSnapshot )
    }

    case msg => {
      log info s">>> AR xfer receiveCommand to concrete class for: ${msg}"
      super.around( r )( msg )
    }
  }

  //DMR: Should accept update state var based on result?
  def accept( event: Any ): S = {
    val result = implicitly[AggregateStateSpecification[S]].accept( state, event )

    val t: Transition = (
      transitionFor( state ) andThen {
        case u => {
          log info s"${self.path.parent.name} transitioning for ${event.getClass.safeSimpleName}"
        }
      }
    ) orElse {
      case ex => log info s"${self.path.parent.name} will not transition state for ${event.getClass.safeSimpleName}"
    }

    t( event )

    result
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
      case m => super.unhandled( m )
    }
  }
}


object AggregateRoot extends StrictLogging {
  val trace = Trace( "AggregateRoot", logger )

  type Transition = PartialFunction[Any, Unit]

  //   val metaLens = lens[Envelope] >> 'header >> 'properties
  //   val messageNumberLens = lens[Envelope] >> 'header >> 'messageNumber
  //   val envelopeUpdateLens = messageNumberLens ~ metaLens
  //   def hasAggregateId( e: Envelope ): Boolean = metaLens.get( e ).contains( HEADER_AGGREGATE_ID )
  //   val newMessageNumber = messageNumber.increment

  //   message match {
  //     case e: Envelope if !hasAggregateId( e ) => {
  //       val properties = addAggregateProperties( metaLens.get( e ) )
  //       envelopeUpdateLens.set( e )( (newMessageNumber, properties) )
  //     }

  //     case m => {
  //       Envelope(
  //         message,
  //         EnvelopeHeader(
  //           fromComponentType = fromComponentType,
  //           fromComponentPath = fromComponentPath,
  //           toComponentPath = ComponentPath.unknown,
  //           messageType = MessageType( message.getClass.safeSimpleName ),
  //           workId = workId,
  //           messageNumber = messageNumber.increment,
  //           version = version,
  //           properties = addAggregateProperties( Map() )
  //         )
  //       )
  //     }
  //   }
  // }
}
