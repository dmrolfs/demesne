package demesne

import peds.archetype.domain.model.core.Identifying


/**
  * Created by rolfsd on 6/20/16.
  */
abstract class AggregateProtocol[I] {
  type Command = AggregateRootModule.Command[I]
  type Event = AggregateRootModule.Event[I]
}
