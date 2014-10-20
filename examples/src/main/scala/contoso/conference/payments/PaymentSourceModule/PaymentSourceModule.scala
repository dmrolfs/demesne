package contoso.conference.payments

import demesne.EventLike
import peds.commons.identifier.{TaggedID, ShortUUID}


/**
 * Created by damonrolfs on 9/12/14.
 */
trait PaymentSourceModule {

}

object PaymentSourceModule { module =>
  type ID = ShortUUID
  type TID = TaggedID[ID]

  sealed trait Event extends EventLike {
    override type ID = module.ID
    // override val sourceTypeName: Option[String] = Some( "PaymentSource" )
  }


  case class PaymentCompleted( override val sourceId: PaymentCompleted#TID, paymentSourceId: module.TID ) extends Event
}
