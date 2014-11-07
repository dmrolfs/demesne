package demesne

import akka.actor._
import akka.event.LoggingReceive
import demesne.factory.ActorFactory
import peds.akka.envelope._
import peds.commons.log.Trace


object EnvelopingAggregateRootRepository {
  def props( model: DomainModel, rootType: AggregateRootType, factory: ActorFactory ): Props = {
    Props( new EnvelopingAggregateRootRepository( model, rootType, factory ) )
  }
}

class EnvelopingAggregateRootRepository(
  model: DomainModel,
  rootType: AggregateRootType,
  factory: ActorFactory
) extends AggregateRootRepository( model, rootType, factory ) with EnvelopingActor {
  override val trace = Trace( "EnvelopingAggregateRootRepository", log )

  override def receive: Actor.Receive = LoggingReceive {
    case message => {
      val originalSender = sender
      trace( s"in EnvelopingAggregateRootRepository RECEIVE" )
      aggregateFor( message ).send( message )( originalSender )
    }
  }
}


object AggregateRootRepository {
  val trace = Trace[AggregateRootRepository.type]

  def props( model: DomainModel, rootType: AggregateRootType, factory: ActorFactory ): Props = trace.block( "AggregateRootRepository" ) {
    Props( new AggregateRootRepository( model, rootType, factory ) )
  }
}

/** Supervisor for aggregate root actors. All client commands will go through this actor, who resolves/extracts the
  * aggregate's id from the command and either finds the aggregate or (if there is no such aggregate) creates the new
  * aggregate and delegates the command.
  *
  * In addition to connecting clients with aggregates, this actor is a supervisor responsible for taking care of its
  * child aggregates, handling fault handling and recovery actions.
 */
class AggregateRootRepository( model: DomainModel, rootType: AggregateRootType, factory: ActorFactory )
extends Actor
with EnvelopingActor
with ActorLogging {
  val trace = Trace( "AggregateRootRepository", log )

  override val supervisorStrategy: SupervisorStrategy = rootType.repositorySupervisionStrategy

  override def receive: Actor.Receive = LoggingReceive {
    case c => trace.block( s"AggregateRootRepository.receive:$c" ) { aggregateFor( c ) forward c }
  }

  def aggregateFor( command: Any ): ActorRef = trace.block( "aggregateFor" ) {
    trace( s"command = $command" )
    trace( s"system = ${context.system}" )
    val (id, _) = rootType aggregateIdFor command
    val result = context.child( id )
    result getOrElse { factory( model, Some(context) )( rootType, id ) }
  }
}
