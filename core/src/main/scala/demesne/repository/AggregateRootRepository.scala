package demesne.repository

import scala.concurrent.Future
import akka.Done
import akka.actor._
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.LoggingReceive
import akka.pattern.pipe
import peds.akka.envelope._
import peds.commons.log.Trace
import demesne.{AggregateRootType, DomainModel}


abstract class EnvelopingAggregateRootRepository(
  model: DomainModel,
  rootType: AggregateRootType
) extends AggregateRootRepository( model, rootType) with EnvelopingActor {
  outer: AggregateRootRepository.AggregateContext =>

  override def repository: Receive = {
    case message => {
      val originalSender = sender()
      val aggregate = aggregateFor( message )
      log.debug( "in EnvelopingAggregateRootRepository RECEIVE: aggregate=[{}]", aggregate )
      Option( aggregate ) foreach { _.sendEnvelope( message )( originalSender ) }
    }
  }
}


object AggregateRootRepository {
  val trace = Trace[AggregateRootRepository.type]

  trait AggregateContext {
    def model: DomainModel
    def rootType: AggregateRootType
    def aggregateProps: Props
    def aggregateFor( command: Any ): ActorRef
  }

  trait LocalAggregateContext extends AggregateContext { self: Actor with ActorLogging =>
    override def aggregateFor( command: Any ): ActorRef = trace.block( "aggregateFor" ) {
      log.debug( "command=[{}]", command.toString )
      if ( !rootType.aggregateIdFor.isDefinedAt(command) ) {
        log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
      }
      val (id, _) = rootType aggregateIdFor command
      context.child( id ) getOrElse { context.actorOf( aggregateProps, id ) }
    }
  }

  trait ClusteredAggregateContext extends AggregateContext { self: Actor with ActorLogging =>
    override def aggregateFor( command: Any ): ActorRef = trace.block( "aggregateFor" ) {
      log.debug( "command=[{}]", command.toString )
      if ( !rootType.aggregateIdFor.isDefinedAt(command) ) {
        log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
      }
      val (id, _) = rootType aggregateIdFor command
      ClusterSharding( model.system ) shardRegion rootType.name
    }
  }
}

/** Supervisor for aggregate root actors. All client commands will go through this actor, who resolves/extracts the
  * aggregate's id from the command and either finds the aggregate or (if there is no such aggregate) creates the new
  * aggregate and delegates the command.
  *
  * In addition to connecting clients with aggregates, this actor is a supervisor responsible for taking care of its
  * child aggregates, handling fault handling and recovery actions.
 */
abstract class AggregateRootRepository( override val model: DomainModel, override val rootType: AggregateRootType )
extends Actor
with EnvelopingActor
with ActorLogging {
  outer: AggregateRootRepository.AggregateContext =>

  import demesne.repository.{ StartProtocol => SP }

  def doLoad(): Future[SP.Loaded] = {
    Future successful { SP.Loaded(rootType, resources = Map.empty[Symbol, Any], dependencies = Set.empty[Symbol]) }
  }

  def doInitialize( resources: Map[Symbol, Any] ): Future[Done] = Future successful { Done }

  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  override def receive: Actor.Receive = LoggingReceive { quiscent }

  implicit val ec = context.dispatcher

  val quiscent: Receive = {
    case SP.Load => {
      val coordinator = sender()
      doLoad() pipeTo coordinator
    }

    case SP.Initialize( resources ) => {
      val coordinator = sender()
      val reply = doInitialize( resources ) map { _ => SP.Started }
      reply pipeTo coordinator
      context become LoggingReceive { nonfunctional orElse repository }
    }
  }

  def nonfunctional: Receive = {
    case Passivate( stop ) => {
      log.debug(
        "passivate received by repository so not in clustered mode. sending stop-message:[{}] back to entity:[{}]",
        stop,
        sender()
      )
      sender() ! stop
    }

    case Envelope( Passivate(stop), _ ) => {
      log.debug(
        "passivate received by repository so not in clustered mode. sending stop-message:[{}] back to entity:[{}]",
        stop,
        sender()
      )
      sender() ! stop
    }

    case SP.WaitForStart => sender( ) ! SP.Started
  }

  def repository: Receive = {
    case c => aggregateFor( c ) forward c
  }
}
