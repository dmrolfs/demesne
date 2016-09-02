package demesne.module


/**
  * Created by rolfsd on 8/31/16.
  */
sealed trait AggregateEnvironment

case object ClusteredAggregate extends AggregateEnvironment

case object LocalAggregate extends AggregateEnvironment
