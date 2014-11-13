package demesne.register

import akka.event.{ActorEventBus, SubchannelClassification}
import akka.util.Subclassification
import com.typesafe.scalalogging.LazyLogging
import peds.akka.envelope.Envelope
import peds.akka.publish.Publisher
import peds.commons.log.Trace


trait RegisterBusProvider {
  def registerBus: RegisterBus
}


object RegisterBus extends LazyLogging {
  val trace = Trace[RegisterBus]
  case class RecordingEvent( topic: String, recording: Any )

  def bus( b: RegisterBus )( spec: FinderSpec[_,_] ): Publisher = ( event: Envelope ) => trace.block( "bus" ) {
    b.publish( RegisterBus.RecordingEvent( topic = spec.relayClassifier, recording = event ) )
    Left( event )
  }
}

/**
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
