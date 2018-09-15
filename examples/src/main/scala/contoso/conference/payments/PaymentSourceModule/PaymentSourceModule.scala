package contoso.conference.payments

import contoso.conference.registration.RegistrationSagaState
import demesne.AggregateProtocol

/**
  * Created by damonrolfs on 9/12/14.
  */

object PaymentSourceProtocol
    extends AggregateProtocol[RegistrationSagaState, RegistrationSagaState#ID] {

  case class PaymentCompleted(
    override val sourceId: PaymentCompleted#TID,
    paymentSourceId: PaymentSourceModule#TID
  ) extends Event
}

trait PaymentSourceModule {
//work here
  type TID
}

object PaymentSourceModule { module =>
//  type ID = ShortUUID
//  type TID = TaggedID[ID]

}
