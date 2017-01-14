package demesne

import akka.Done
import scala.concurrent.duration._
import akka.actor.{ActorSystem, Props}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import com.typesafe.scalalogging.LazyLogging
import demesne.index.IndexSpecification
import shapeless.TypeCase
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.identifier.{Identifying, TaggedID}


object AggregateRootType {
  trait Provider {
    def rootType: AggregateRootType
  }
}

abstract class AggregateRootType extends Equals with LazyLogging {
  def name: String
  def repositoryName: String = org.atteo.evo.inflector.English.plural( name )

  def startTask: StartTask = StartTask.empty( name )

  def repositoryProps( implicit model: DomainModel ): Props

  val identifying: Identifying[_]
  val TaggedIdType: TypeCase[TaggedID[_]] = TypeCase[TaggedID[_]]

  //todo: separate envelope & reliable like Relay's fillExtractor
  def aggregateIdFor: ShardRegion.ExtractEntityId = {
    case cmd: CommandLike => ( cmd.targetId.id.toString, cmd )
    case event: EventLike => ( event.sourceId.id.toString, event )
    case msg: MessageLike => ( msg.targetId.id.toString, msg )
    case e: EntityEnvelope => ( e.id.toString, e )
    case e @ Envelope( payload, _ ) if aggregateIdFor.isDefinedAt( payload ) => ( aggregateIdFor(payload)._1, e ) // want MatchError on payload if not found
    case r @ ReliableMessage( _, msg ) if aggregateIdFor.isDefinedAt( msg ) => ( aggregateIdFor(msg)._1, r )  // want MatchError on msg if not found
    case p @ Passivate( stop ) if aggregateIdFor.isDefinedAt( stop ) => ( aggregateIdFor(stop)._1, p )
    // case stop @ PassivationSpecification.StopAggregateRoot( TaggedIdType(tid) ) => {
    //   logger.debug( "tagged aggregateIdFor(stop) = [{}]", (tid.id.toString, stop) )
    //   ( tid.id.toString, stop )
    // }
    // case stop @ PassivationSpecification.StopAggregateRoot( id ) => {
    //   logger.debug( "untagged aggregateIdFor(stop) = [{}]", (id.toString, stop) )
    //   ( id.toString, stop )
    // }
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
    case event: EventLike => ( math.abs( event.sourceId.id.## ) % numberOfShards ).toString
    case msg: MessageLike => ( math.abs( msg.targetId.id.## ) % numberOfShards ).toString
    case e: EntityEnvelope => ( math.abs( e.id.## ) % numberOfShards ).toString
    case Envelope( payload, _ ) => shardIdFor( payload )
    case ReliableMessage( _, msg ) => shardIdFor( msg )
    case Passivate( stop ) => shardIdFor( stop )
    // case stop @ PassivationSpecification.StopAggregateRoot( TaggedIdType(tid) ) => {
    //   logger.debug( "tagged shardIdFor(stop) = [{}]", ( math.abs( tid.id.## ) % numberOfShards ).toString )
    //   ( math.abs( tid.id.## ) % numberOfShards ).toString
    // }
    // case stop @ PassivationSpecification.StopAggregateRoot( id ) => {
    //   logger.debug( "untagged shardIdFor(stop) = [{}]", ( math.abs( id.## ) % numberOfShards ).toString )
    //   ( math.abs( id.## ) % numberOfShards ).toString
    // }
  }

  /**
    * specify the period of inactivity before the entity passivates
    */
  def passivateTimeout: Duration = 15.minutes
  def passivation: PassivationSpecification = new PassivationSpecification {
    override val inactivityTimeout: Duration = passivateTimeout
  }

  def snapshotPeriod: Option[FiniteDuration] = Some( 15.minutes )
  def snapshot: Option[SnapshotSpecification] = {
    snapshotPeriod map { period =>
      new SnapshotSpecification {
        override val snapshotInitialDelay: FiniteDuration = period
        override val snapshotInterval: FiniteDuration = period
      }
    }
  }

  def indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification]


  // far from ideal practice, but aggregate roots can create their own anonymous root types.
  override def canEqual( that: Any ): Boolean = that.isInstanceOf[AggregateRootType]

  override def equals( rhs: Any ): Boolean = {
    rhs match {
      case that: AggregateRootType => {
        if ( this eq that ) true
        else {
          ( that.## == this.## ) &&
          ( that canEqual this ) &&
          ( this.name == that.name ) &&
          ( this.identifying == that.identifying )
        }
      }

      case _ => false
    }
  }

  override def hashCode(): Int = 41 * ( 41 + name.## ) + identifying.##

  override def toString: String = name + "AggregateRootType"
}
