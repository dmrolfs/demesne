package demesne.module

import akka.cluster.sharding.ShardRegion
import demesne.{ AggregateRootType, DomainModel }

/**
  * Created by rolfsd on 8/31/16.
  */
sealed trait AggregateEnvironment

object AggregateEnvironment {
  trait Resolver extends (DomainModel => AggregateEnvironment )

  object Resolver {
    val local: Resolver = (m: DomainModel) => LocalAggregate

    val clustered: Resolver = (m: DomainModel) => {
      ClusteredAggregate(
        toExtractEntityId = (rt: AggregateRootType) => rt.aggregateIdFor,
        toExtractShardId = (rt: AggregateRootType) => rt.shardIdFor
      )
    }
  }
}

case class ClusteredAggregate(
  toExtractEntityId: AggregateRootType => ShardRegion.ExtractEntityId = (rt: AggregateRootType) =>
    rt.aggregateIdFor,
  toExtractShardId: AggregateRootType => ShardRegion.ExtractShardId = (rt: AggregateRootType) =>
    rt.shardIdFor
) extends AggregateEnvironment

case object LocalAggregate extends AggregateEnvironment
