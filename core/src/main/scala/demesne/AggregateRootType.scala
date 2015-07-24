package demesne

import akka.actor.{ActorRef, Props, SupervisorStrategy}
import akka.contrib.pattern.ShardRegion
import demesne.register.AggregateIndexSpec
import peds.archetype.domain.model.core.Identifiable
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.util._

import scala.concurrent.duration._


object AggregateRootType {
  trait Provider {
    def meta: AggregateRootType
  }
}

trait AggregateRootType {
  type ID
  type TID = Identifiable.TID[ID]
  
  def name: String
  def repositoryName: String = name+"Repository"

  def aggregateRootProps( implicit model: DomainModel ): Props

  def aggregateIdOf( aggregateRoot: ActorRef ): String = aggregateRoot.path.name

  //todo: separate envelope & reliable like Relay's fillExtractor
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

  def indexes: Seq[DomainModel.AggregateIndexSpecLike] = Seq.empty[DomainModel.AggregateIndexSpecLike]

  def repositorySupervisionStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  override def toString: String = name + "AggregateRootType"
}
