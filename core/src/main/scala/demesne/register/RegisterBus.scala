package demesne.register

import akka.event.{ActorEventBus, SubchannelClassification}
import akka.util.Subclassification
import com.typesafe.scalalogging.LazyLogging
import demesne.AggregateRootType
import peds.akka.envelope.Envelope
import peds.akka.publish.Publisher
import peds.commons.log.Trace


object RegisterBus extends LazyLogging {
  val trace = Trace[RegisterBus]

  /**
   * Message used to relay an event to the [[AggregateRegister]].
   */
  case class RecordingEvent( topic: String, recording: Any )

  /**
   * create a publisher corresponding to the system's register bus and a topic based on the root type.
   */
  def bus( b: RegisterBus, rootType: AggregateRootType )( spec: FinderSpec[_,_] ): Publisher = {
    ( event: Envelope ) => trace.block( "bus" ) {
      b.publish( RegisterBus.RecordingEvent( topic = spec.relayClassifier(rootType), recording = event ) )
      Left( event )
    }
  }
}

/**
 * RegisterBus connects the register mechanism via Akka's EventBus framework. The rebister bus is used to route aggregate
 * events to the [[RegisterAggregate]] who maintains the local index for the Aggregate Root.
 * Created by damonrolfs on 11/1/14.
 */
class RegisterBus extends ActorEventBus with SubchannelClassification {
  import demesne.register.RegisterBus._

  override type Event = RecordingEvent
  override type Classifier = String


  override protected implicit def subclassification: Subclassification[Classifier] = new Subclassification[Classifier] {
    override def isEqual( x: Classifier, y: Classifier ): Boolean = x == y

    override def isSubclass( x: Classifier, y: Classifier ): Boolean = x startsWith y
  }


  override protected def classify( event: Event ): Classifier = event.topic

  override protected def publish( event: Event, subscriber: Subscriber ): Unit = subscriber ! event.recording
}


// trait RegisterBusProvider {
//   def registerBus: RegisterBus
// }
