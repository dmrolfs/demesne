package demesne

import omnibus.identifier.Identifying

/**
  * Created by rolfsd on 6/20/16.
  */
abstract class AggregateProtocol[E: Identifying] { outer =>
  type ID = Identifying[E]#ID

  trait ProtocolMessage
  trait Message extends AggregateRootModule.Message[E] with ProtocolMessage
  trait Command extends AggregateRootModule.Command[E] with ProtocolMessage
  trait Event extends AggregateRootModule.Event[E] with ProtocolMessage
}
