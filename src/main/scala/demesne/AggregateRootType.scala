package demesne

import akka.actor.{ActorRef, Props}
import akka.contrib.pattern.ShardRegion
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliableMessage
import peds.commons.util._

import scala.concurrent.duration._


trait AggregateRootType {
  def name: String
  def repositoryName: String = name+"Repository"

  // def actorFactory: ActorFactory
  def aggregateRootProps: Props

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

  def passivation: PassivationSpecification = new PassivationSpecification {
    override val inactivityTimeout: Duration = 2.minutes
  }

  def snapshot: SnapshotSpecification = new SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 1.minute
    override val snapshotInterval: FiniteDuration = 1.minute
  }

  override def toString: String = getClass.safeSimpleName
}
