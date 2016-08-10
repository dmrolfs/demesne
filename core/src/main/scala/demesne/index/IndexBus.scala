package demesne.index

import akka.event.{ActorEventBus, SubchannelClassification}
import akka.util.Subclassification
import com.typesafe.scalalogging.LazyLogging
import demesne.AggregateRootType
import peds.akka.envelope.Envelope
import peds.akka.publish.Publisher
import peds.commons.log.Trace


object IndexBus extends LazyLogging {
  val trace = Trace[IndexBus]

  /**
   * Message used to relay an event to the [[demesne.index.IndexAggregate]].
   */
  case class RecordingEvent( topic: String, recording: Any )

  /**
   * create a publisher corresponding to the system's index bus and a topic based on the root type.
   */
  def bus(b: IndexBus, rootType: AggregateRootType )(spec: AggregateIndexSpec[_,_] ): Publisher = {
    ( event: Any ) => trace.block( "bus" ) {
      b.publish( IndexBus.RecordingEvent( topic = spec.relayClassifier( rootType ), recording = event ) )
      Left( event )
    }
  }
}

/**
 * IndexBus connects the index mechanism via Akka's EventBus framework. The rebister bus is used to route aggregate
 * events to the [[IndexAggregate]] who maintains the local index for the Aggregate Root.
 * Created by damonrolfs on 11/1/14.
 */
class IndexBus extends ActorEventBus with SubchannelClassification {
  import demesne.index.IndexBus._

  override type Event = RecordingEvent
  override type Classifier = String


  override protected implicit def subclassification: Subclassification[Classifier] = new Subclassification[Classifier] {
    override def isEqual( x: Classifier, y: Classifier ): Boolean = x == y

    override def isSubclass( x: Classifier, y: Classifier ): Boolean = x startsWith y
  }


  override protected def classify( event: Event ): Classifier = event.topic

  override protected def publish( event: Event, subscriber: Subscriber ): Unit = subscriber ! event.recording
}
