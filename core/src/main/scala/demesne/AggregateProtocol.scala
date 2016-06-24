package demesne


/**
  * Created by rolfsd on 6/20/16.
  */
abstract class AggregateProtocol[I] {
  type Command = AggregateRootModule.Command[I]
  type Event = AggregateRootModule.Event[I]
  trait Message
  trait CommandMessage extends Command with Message
  trait EventMessage extends Event with Message
}
