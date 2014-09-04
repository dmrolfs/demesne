package demesne

import akka.actor.{ Actor, ActorRef, ActorLogging, Props }
import akka.contrib.pattern.ShardRegion
import akka.event.LoggingReceive
import peds.akka.envelope._
import peds.commons.log.Trace


class EnvelopingAggregateRootRepository(
  aggregateRootType: AggregateRootType
) extends AggregateRootRepository( aggregateRootType ) with EnvelopingActor {
  override val trace = Trace( "EnvelopingAggregateRootRepository", log )

  override def receive: Actor.Receive = LoggingReceive {
    case message => {
      val originalSender = sender
      trace( s"in EnvelopingAggregateRootRepository RECEIVE" )
      aggregateFor( message ).send( message )( originalSender )
    }
  }
}

object EnvelopingAggregateRootRepository {
  def props( rootType: AggregateRootType ): Props = Props( new EnvelopingAggregateRootRepository( rootType ) )

  def specificationFor( rootType: AggregateRootType ): AggregateRootRepository.ClusterShardingSpecification = {
    val name = rootType.name

    AggregateRootRepository.ClusterShardingSpecification(
      name = s"${name}Repository", 
      props = props( rootType ),
      idExtractor = { case c => ( name, c ) }, 
      shardResolver = { case c => ( math.abs( name.hashCode ) % 100 ).toString }
    )
  }
}


/**
 * Supervisor for aggregate root actors. All client commands will go through this actor, who resolves/extracts the aggregate's id
 * from the command and either finds the aggregate or (if there is no such aggregate) creates the new aggregate and delegates the
 * command. 
 *
 * In addition to connecting clients with aggregates, this actor is a supervisor responsible for taking care of its child 
 * aggregates, handling fault handling and recovery actions.
 */
class AggregateRootRepository( aggregateRootType: AggregateRootType ) extends Actor with EnvelopingActor with ActorLogging {
  val trace = Trace( "AggregateRootRepository", log )

  override def receive: Actor.Receive = LoggingReceive {
    case command => {
      val originalSender = sender
      trace( s"in AggregateRootRepository RECEIVE" )
      aggregateFor( command ).tell( command, originalSender )
    }
  }

  def aggregateFor( command: Any ): ActorRef = trace.block( "aggregateFor" ) {
    trace( s"command = $command" )
    val originalSender = sender
    val (id, cmd) = aggregateRootType aggregateIdFor command
    getOrCreateChild( aggregateRootType.aggregateRootProps, id )
  }

  def getOrCreateChild( aggregateProps: Props, name: String ): ActorRef = trace.block( "getOrCreateChild" ) {
    context.child( name ) getOrElse { context.actorOf( aggregateProps, name ) }
  }
}

object AggregateRootRepository {
  val trace = Trace[AggregateRootRepository.type]
  def props( rootType: AggregateRootType ): Props = trace.block( "AggregateRootRepository" ) { 
    Props( classOf[AggregateRootRepository], rootType )
  }

  case class ClusterShardingSpecification( 
    name: String, 
    props: Props,
    idExtractor: ShardRegion.IdExtractor, 
    shardResolver: ShardRegion.ShardResolver 
  )

  def specificationFor( rootType: AggregateRootType ): ClusterShardingSpecification = {
    val name = rootType.name

    ClusterShardingSpecification(
      name = s"${name}Repository", 
      props = props( rootType ),
      idExtractor = { case c => ( name, c ) }, 
      shardResolver = { case c => ( math.abs( name.hashCode ) % 100 ).toString }
    )
  }
}
