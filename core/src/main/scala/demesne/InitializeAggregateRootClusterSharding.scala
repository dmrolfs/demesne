package demesne

import akka.Done

import scala.concurrent.{ExecutionContext, Future}
import scalaz._
import Scalaz._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import peds.commons.Valid


trait InitializeAggregateRootClusterSharding extends CommonInitializeAggregateActorType { self: AggregateRootType.Provider =>
  abstract override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ): Valid[Future[Done]] = peds.commons.log.Trace("InitializeAggregateRootClusterSharding").block( "initializer" ) {
    super.initializer( rootType, model, props )

    ClusterSharding( model.system )
      .start(
        typeName = rootType.name,
        entityProps = rootType.aggregateRootProps(model),
        settings = ClusterShardingSettings(model.system),
        extractEntityId = rootType.aggregateIdFor,
        extractShardId = rootType.shardIdFor
      )

    Future.successful{ Done }.successNel
  }
}
