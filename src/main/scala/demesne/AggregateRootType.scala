package demesne

import akka.actor.{ActorRef, Props}
import akka.contrib.pattern.ShardRegion
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.util._

import scala.concurrent.duration._


trait AggregateRootType {
  def name: String
  def repositoryName: String = name+"Repository"

  // def actorFactory: ActorFactory
  def aggregateRootProps( implicit model: DomainModel ): Props

  //DMR: AggregateRootModuleCompanion.shardName???  How to get that?  or at least DRY them up?
  def aggregateIdOf( aggregateRoot: ActorRef ): String = aggregateRoot.path.name

  def aggregateIdFor: ShardRegion.IdExtractor = {
    case cmd: CommandLike => ( cmd.targetId.toString, cmd )
    case e @ Envelope( payload, _ ) if aggregateIdFor.isDefinedAt( payload ) => ( aggregateIdFor( payload )._1, e ) // want MatchError on payload if not found
    case r @ ReliableMessage( _, msg ) if aggregateIdFor.isDefinedAt( msg ) => ( aggregateIdFor( msg )._1, r )  // want MatchError on msg if not found
  }

  def shardIdFor: ShardRegion.ShardResolver = {
    case cmd: CommandLike => ( math.abs( cmd.targetId.hashCode ) % 100 ).toString
    case e @ Envelope( payload, _ ) => shardIdFor( payload )
    case r @ ReliableMessage( _, msg ) => shardIdFor( msg )
  }

  //todo: make configuration driven
  def passivation: PassivationSpecification = new PassivationSpecification {
    override val inactivityTimeout: Duration = 2.minutes
  }

  //todo: make configuration driven
  def snapshot: SnapshotSpecification = new SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 1.minute
    override val snapshotInterval: FiniteDuration = 1.minute
  }

  override def toString: String = getClass.safeSimpleName
}


// trait ModelProvider {
//   def model: DomainModel
// }


// class ModeledAggregateRootType(
//   underlying: AggregateRootType,
//   override val model: DomainModel
// ) extends AggregateRootType with ModelProvider {
//   override def name: String = underlying.name
//   override def repositoryName: String = underlying.repositoryName
//   override def aggregateRootProps( implicit model: DomainModel ): Props = underlying.aggregateRootProps( model )
//   override def aggregateIdOf( aggregateRoot: ActorRef ): String = underlying.aggregateIdOf( aggregateRoot )
//   override def aggregateIdFor: ShardRegion.IdExtractor = underlying.aggregateIdFor
//   override def shardIdFor: ShardRegion.ShardResolver = underlying.shardIdFor
//   override def passivation: PassivationSpecification = underlying.passivation
//   override def snapshot: SnapshotSpecification = underlying.snapshot
//   override def toString: String = underlying.toString
// }
