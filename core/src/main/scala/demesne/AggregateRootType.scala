package demesne

import scala.concurrent.duration._
import akka.actor.{ ActorRef, Props, SupervisorStrategy }
import akka.cluster.sharding.ShardRegion
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliablePublisher.ReliableMessage


object AggregateRootType {
  trait Provider {
    def meta: AggregateRootType
  }
}

trait AggregateRootType { outer =>
  type ID
  type TID
  
  def name: String
  def repositoryName: String = name+"Repository"

  def aggregateRootProps( implicit model: DomainModel ): Props

//  def aggregateIdOf( aggregateRoot: ActorRef ): String = aggregateRoot.path.name

  //todo: separate envelope & reliable like Relay's fillExtractor
  def aggregateIdFor: ShardRegion.ExtractEntityId = {
    case cmd: CommandLike => ( cmd.targetId.toString, cmd )
    case e @ Envelope( payload, _ ) if aggregateIdFor.isDefinedAt( payload ) => ( aggregateIdFor( payload )._1, e ) // want MatchError on payload if not found
    case r @ ReliableMessage( _, msg ) if aggregateIdFor.isDefinedAt( msg ) => ( aggregateIdFor( msg )._1, r )  // want MatchError on msg if not found
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
    case cmd: CommandLike => ( math.abs( cmd.targetId.hashCode ) % numberOfShards ).toString
    case e @ Envelope( payload, _ ) => shardIdFor( payload )
    case r @ ReliableMessage( _, msg ) => shardIdFor( msg )
  }

  //todo: make configuration driven
  /**
    * specify the period of inactivity before the entity passivates
    */
  def passivation: PassivationSpecification = new PassivationSpecification {
    override val inactivityTimeout: Duration = 15.minutes
  }

  //todo: make configuration driven
  def snapshot: SnapshotSpecification = new SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 15.minutes
    override val snapshotInterval: FiniteDuration = 15.minutes
  }

  def indexes: Seq[DomainModel.AggregateIndexSpecLike] = Seq.empty[DomainModel.AggregateIndexSpecLike]

  def repositorySupervisionStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  override def toString: String = name + "AggregateRootType"
}
