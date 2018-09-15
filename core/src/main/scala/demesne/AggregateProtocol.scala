package demesne

import omnibus.identifier.Identifying

/**
  * Created by rolfsd on 6/20/16.
  */
abstract class AggregateProtocol[E, ID]( implicit val identifying: Identifying.Aux[E, ID] ) {
  trait ProtocolMessage
  abstract class Message extends AggregateRootModule.Message[E, ID] with ProtocolMessage
  abstract class Command extends AggregateRootModule.Command[E, ID] with ProtocolMessage
  abstract class Event extends AggregateRootModule.Event[E, ID] with ProtocolMessage
}
