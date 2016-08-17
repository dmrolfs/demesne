package demesne

import scala.concurrent.duration._
import akka.actor.{Props, SupervisorStrategy}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import com.typesafe.scalalogging.LazyLogging
import demesne.index.IndexSpecification
import shapeless.TypeCase
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.identifier.TaggedID


object AggregateRootType {
  trait Provider {
    def rootType: AggregateRootType
  }
}

trait AggregateRootType extends LazyLogging {
  def name: String
  def repositoryName: String = name+"Repository"

  def aggregateRootProps( implicit model: DomainModel ): Props

  val TaggedIdType = TypeCase[TaggedID[_]]

  //todo: separate envelope & reliable like Relay's fillExtractor
  def aggregateIdFor: ShardRegion.ExtractEntityId = {
    case cmd: CommandLike => ( cmd.targetId.id.toString, cmd )
    case stop @ PassivationSpecification.StopAggregateRoot( TaggedIdType(tid) ) => {
      logger.debug( "tagged aggregateIdFor(stop) = [{}]", (tid.id.toString, stop) )
      ( tid.id.toString, stop )
    }
    case stop @ PassivationSpecification.StopAggregateRoot( id ) => {
      logger.debug( "untagged aggregateIdFor(stop) = [{}]", (id.toString, stop) )
      ( id.toString, stop )
    }
    case e: EntityEnvelope => ( e.id.toString, e )
    case e @ Envelope( payload, _ ) if aggregateIdFor.isDefinedAt( payload ) => ( aggregateIdFor(payload)._1, e ) // want MatchError on payload if not found
    case r @ ReliableMessage( _, msg ) if aggregateIdFor.isDefinedAt( msg ) => ( aggregateIdFor(msg)._1, r )  // want MatchError on msg if not found
    case p @ Passivate( stop ) if aggregateIdFor.isDefinedAt( stop ) => ( aggregateIdFor(stop)._1, p )
  }

  /**
    * Specify the maximum planned number of cluster nodes.
    */
  def maximumNrClusterNodes: Int = 3

  /**
    * Good rule of thumb is to set number of shards to 10 times the maximum planned number of cluster nodes.
    */
  def numberOfShards: Int = 10 * maximumNrClusterNodes

  def shardIdFor: ShardRegion.ExtractShardId = {
    case cmd: CommandLike => ( math.abs( cmd.targetId.id.## ) % numberOfShards ).toString
    case stop @ PassivationSpecification.StopAggregateRoot( TaggedIdType(tid) ) => {
      logger.debug( "tagged shardIdFor(stop) = [{}]", ( math.abs( tid.id.## ) % numberOfShards ).toString )
      ( math.abs( tid.id.## ) % numberOfShards ).toString
    }
    case stop @ PassivationSpecification.StopAggregateRoot( id ) => {
      logger.debug( "untagged shardIdFor(stop) = [{}]", ( math.abs( id.## ) % numberOfShards ).toString )
      ( math.abs( id.## ) % numberOfShards ).toString
    }
    case e: EntityEnvelope => ( math.abs( e.id.## ) % numberOfShards ).toString
    case Envelope( payload, _ ) => shardIdFor( payload )
    case ReliableMessage( _, msg ) => shardIdFor( msg )
    case Passivate( stop ) => shardIdFor( stop )
  }

  /**
    * specify the period of inactivity before the entity passivates
    */
  def passivateTimeout: Duration = 15.minutes
  def passivation: PassivationSpecification = new PassivationSpecification {
    override val inactivityTimeout: Duration = passivateTimeout
  }

  def snapshotPeriod: FiniteDuration = 15.minutes
  def snapshot: SnapshotSpecification = new SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = snapshotPeriod
    override val snapshotInterval: FiniteDuration = snapshotPeriod
  }

  def indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification]

  def repositorySupervisionStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  override def toString: String = name + "AggregateRootType"
}
