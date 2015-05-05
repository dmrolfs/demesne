package demesne

import akka.persistence.SnapshotOffer
import com.typesafe.scalalogging.LazyLogging
import peds.commons.util._

import scala.reflect.ClassTag


trait AggregateStateSpecification[S] extends LazyLogging { outer =>
  type Acceptance = PartialFunction[Any, S]

  def acceptance( state: S ): Acceptance

  def accept( state: S, event: Any ): S = {
    val a = acceptance( state )

    if ( a.isDefinedAt( event ) ) a( event )
    else {
      logger debug s"""${Option(state).map{_.getClass.safeSimpleName}} does not accept event ${event.getClass.safeSimpleName}"""
      state
    }
  }

  def acceptSnapshot( state: S, snapshotOffer: SnapshotOffer )( implicit evS: ClassTag[S] ): S = {
    accept( state, snapshotOffer.snapshot )
  }
}
