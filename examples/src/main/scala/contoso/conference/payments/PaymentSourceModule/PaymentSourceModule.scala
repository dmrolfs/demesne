package contoso.conference.payments

import demesne.AggregateProtocol
import omnibus.commons.identifier.{ShortUUID, TaggedID}


/**
 * Created by damonrolfs on 9/12/14.
 */

object PaymentSourceProtocol extends AggregateProtocol[ShortUUID] {
  case class PaymentCompleted(
    override val sourceId: PaymentCompleted#TID,
    paymentSourceId: PaymentSourceModule.TID
  ) extends Event
}


trait PaymentSourceModule {
//work here
}

object PaymentSourceModule { module =>
  type ID = ShortUUID
  type TID = TaggedID[ID]

}
