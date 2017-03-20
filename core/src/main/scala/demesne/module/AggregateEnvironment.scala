package demesne.module

import akka.actor.ActorSystem
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import demesne.AggregateRootType


/**
  * Created by rolfsd on 8/31/16.
  */
sealed trait AggregateEnvironment

case class ClusteredAggregate(
  toSettings: ActorSystem => ClusterShardingSettings = (s: ActorSystem) => ClusterShardingSettings( s ),
  toExtractEntityId: AggregateRootType => ShardRegion.ExtractEntityId = (rt: AggregateRootType) => rt.aggregateIdFor,
  toExtractShardId: AggregateRootType => ShardRegion.ExtractShardId = (rt: AggregateRootType) => rt.shardIdFor
) extends AggregateEnvironment

case object LocalAggregate extends AggregateEnvironment
