// package demesne

// import scala.reflect.ClassTag
// import akka.persistence.SnapshotOffer
// import com.typesafe.scalalogging.LazyLogging
// import omnibus.commons.util._
// object AggregateStateSpecification {
//   type Acceptance[S] = PartialFunction[(Any, S), S]
// }

// trait AggregateStateSpecification[S] extends LazyLogging { outer =>
//   import AggregateStateSpecification._

//   def acceptance: Acceptance[S]

//   def accept( state: S, event: Any ): S = {
//     logger debug s"StateSpec:state=$state"
//     logger debug s"StateSpec:event=$event"
//     val eventState = (event, state)
//     if ( acceptance.isDefinedAt( eventState ) ) acceptance( eventState )
//     else {
//       logger debug s"""${Option(state).map{_.getClass.safeSimpleName}} does not accept event ${event.getClass.safeSimpleName}"""
//       state
//     }
//   }

//   def acceptSnapshot( state: S, snapshotOffer: SnapshotOffer )( implicit evS: ClassTag[S] ): S = {
//     accept( state, snapshotOffer.snapshot )
//   }
// }
