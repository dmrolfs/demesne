package demesne.repository

import scala.concurrent.{ ExecutionContext, Future }
import akka.Done
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import net.ceedubs.ficus.Ficus._
import demesne.{ AggregateRootType, DomainModel }
import omnibus.core.EC

/**
  * Created by rolfsd on 3/29/17.
  */
trait AggregateContext {
  def model: DomainModel
  def rootType: AggregateRootType
  def aggregateProps: Props
  def aggregateFor( command: Any ): ActorRef
  def loadContext[_: EC](): Future[Done] = Future successful Done

  def initializeContext[_: EC]( resources: Map[Symbol, Any] ): Future[Done] = Future successful Done
}

trait LocalAggregateContext extends AggregateContext with ActorLogging { actor: Actor =>
  override def aggregateFor( command: Any ): ActorRef = {
    if (!rootType.aggregateIdFor.isDefinedAt( command )) {
      log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
    }
    val ( id, _ ) = rootType aggregateIdFor command
    context.child( id ) getOrElse { context.actorOf( aggregateProps, id ) }
  }
}

trait ClusteredAggregateContext extends AggregateContext with ActorLogging { actor: Actor =>
  def settings: ClusterShardingSettings = ClusterShardingSettings( model.system )
  def extractEntityId: ShardRegion.ExtractEntityId = rootType.aggregateIdFor
  def extractShardId: ShardRegion.ExtractShardId = rootType.shardIdFor

  override def initializeContext[_: EC]( resources: Map[Symbol, Any] ): Future[Done] = {
    Future {
      if (!isAggregateProxied) {
        val region = ClusterSharding( model.system ).start(
          typeName = rootType.name,
          entityProps = aggregateProps,
          settings = this.settings,
          extractEntityId = this.extractEntityId,
          extractShardId = this.extractShardId
        )

        log.info( "cluster shard started for root-type:[{}] region:[{}]", rootType.name, region )
      } else {
        val region = ClusterSharding( model.system ).startProxy(
          typeName = rootType.name,
          role = rootType.clusterRole,
          extractEntityId = this.extractEntityId,
          extractShardId = this.extractShardId
        )

        log.info(
          "cluster shard proxy started for root-type:[{}] region:[{}]",
          rootType.name,
          region
        )
      }

      Done
    }
  }

  private lazy val isAggregateProxied: Boolean = {
    val nodeRoles = model.system.settings.config.as[Option[Set[String]]]( "akka.cluster.roles" )

    val result = {
      rootType.clusterRole
        .map { role =>
          nodeRoles map { roles =>
            !roles.contains( role )
          } getOrElse true
        }
        .getOrElse { false }
    }

    log.info(
      "is aggregate cluster shard proxied:[{}] root-type-role:[{}] node-roles:[{}]",
      result.toString,
      rootType.clusterRole.toString,
      nodeRoles.toString
    )

    result
  }

  override def aggregateFor( command: Any ): ActorRef = {
    if (!rootType.aggregateIdFor.isDefinedAt( command )) {
      log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
    }
    ClusterSharding( model.system ) shardRegion rootType.name
  }
}
