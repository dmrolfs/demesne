package demesne.repository

import akka.actor.Props
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import demesne.{AggregateRootType, DomainModel}


/**
  * Created by rolfsd on 8/31/16.
  */
abstract class CommonRepository( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps )
  extends EnvelopingAggregateRootRepository( model, rootType ) { outer: AggregateContext =>
  override def aggregateProps: Props = makeAggregateProps( model, rootType )
}


object CommonClusteredRepository {
  def props(
    model: DomainModel,
    rootType: AggregateRootType,
    makeAggregateProps: AggregateRootProps
  )(
    settings: ClusterShardingSettings = ClusterShardingSettings( model.system ),
    extractEntityId: ShardRegion.ExtractEntityId = rootType.aggregateIdFor,
    extractShardId: ShardRegion.ExtractShardId = rootType.shardIdFor
  ): Props = {
    Props( new CommonClusteredRepository( model, rootType, makeAggregateProps )( settings, extractEntityId, extractShardId) )
  }
}

class CommonClusteredRepository(
  model: DomainModel,
  rootType: AggregateRootType,
  makeAggregateProps: AggregateRootProps
)(
  override val settings: ClusterShardingSettings = ClusterShardingSettings( model.system ),
  override val extractEntityId: ShardRegion.ExtractEntityId = rootType.aggregateIdFor,
  override val extractShardId: ShardRegion.ExtractShardId = rootType.shardIdFor
)
extends CommonRepository( model, rootType, makeAggregateProps ) with ClusteredAggregateContext



object CommonLocalRepository {
  def props( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps ): Props = {
    Props( new CommonLocalRepository( model, rootType, makeAggregateProps ) )
  }
}

class CommonLocalRepository( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps )
extends CommonRepository( model, rootType, makeAggregateProps ) with LocalAggregateContext
