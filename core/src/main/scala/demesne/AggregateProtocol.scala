package demesne

import peds.commons.identifier.TaggedID


/**
  * Created by rolfsd on 6/20/16.
  */
abstract class AggregateProtocol[I] { outer =>
  type ID = I
  type TID = TaggedID[ID]

  trait ProtocolMessage
  trait Message extends AggregateRootModule.Message[I] with ProtocolMessage
  trait Command extends AggregateRootModule.Command[I] with ProtocolMessage
  trait Event extends AggregateRootModule.Event[I] with ProtocolMessage
}
