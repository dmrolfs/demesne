package demesne.repository

import scala.concurrent.{ExecutionContext, Future}
import akka.Done
import akka.actor._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.LoggingReceive
import akka.pattern.pipe

import scalaz._
import Scalaz._
import peds.akka.envelope._
import peds.commons.log.Trace
import peds.commons.Valid
import demesne.{AggregateRootType, DomainModel}
import demesne.repository.{StartProtocol => SP}
import peds.akka.ActorStack


abstract class EnvelopingAggregateRootRepository(
  model: DomainModel,
  rootType: AggregateRootType
) extends AggregateRootRepository( model, rootType) with EnvelopingActor {
  outer: AggregateRootRepository.AggregateContext =>

  override def repository: Receive = {
    case message => {
      val originalSender = sender()
      val aggregate = aggregateFor( message )
      log.debug( "enveloping-repository:[{}] forwarding to aggregate:[{}] command:[{}]", self.path.name, aggregate, message )
      Option( aggregate ) foreach { _ forwardEnvelope message }
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
    def loadContext()( implicit ec: ExecutionContext ): Future[Done] = Future successful Done
    def initializeContext( resources: Map[Symbol, Any] )( implicit ec: ExecutionContext ): Future[Done] = Future successful Done
  }

  trait LocalAggregateContext extends AggregateContext with ActorLogging { actor: Actor =>
    override def aggregateFor( command: Any ): ActorRef = trace.block( s"aggregateFor(${command})" ) {
      if ( !rootType.aggregateIdFor.isDefinedAt(command) ) {
        log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
      }
      val (id, _) = rootType aggregateIdFor command
      context.child( id ) getOrElse { context.actorOf( aggregateProps, id ) }
    }
  }

  trait ClusteredAggregateContext extends AggregateContext with ActorLogging { actor: Actor =>
    override def initializeContext( resources: Map[Symbol, Any] )( implicit ec: ExecutionContext ): Future[Done] = trace.block("initializeContext") {
      Future {
        val region = {
          ClusterSharding( model.system )
          .start(
            typeName = rootType.name,
            entityProps = aggregateProps,
            settings = ClusterShardingSettings( model.system ),
            extractEntityId = rootType.aggregateIdFor,
            extractShardId = rootType.shardIdFor
          )
        }

        log.debug( "cluster shard started for root-type:[{}] region:[{}]", rootType.name, region )
        Done
      }
    }

    override def aggregateFor( command: Any ): ActorRef = trace.block( s"aggregateFor(${command})" ) {
      if ( !rootType.aggregateIdFor.isDefinedAt(command) ) {
        log.warning( "AggregateRootType[{}] does not recognize command[{}]", rootType.name, command )
      }
      ClusterSharding( model.system ) shardRegion rootType.name
    }
  }
}

/** AggregateRootRepository for aggregate root actors. All client commands will go through this actor, who resolves/extracts the
  * aggregate's id from the command and either finds the aggregate or (if there is no such aggregate) creates the new
  * aggregate and delegates the command.
  *
  * In addition to connecting clients with aggregates, this actor is a supervisor responsible for taking care of its
  * child aggregates, handling fault handling and recovery actions.
 */
abstract class AggregateRootRepository( override val model: DomainModel, override val rootType: AggregateRootType )
extends Actor
with ActorStack
with ActorLogging {
  outer: AggregateRootRepository.AggregateContext =>

  def handleLoad()( implicit ec: ExecutionContext ): Future[SP.Loaded] = outer.loadContext() map { _ => doLoad() }

  def doLoad(): SP.Loaded = SP.Loaded(rootType, resources = Map.empty[Symbol, Any], dependencies = Set.empty[Symbol])

  def handleInitialize( resources: Map[Symbol, Any] )( implicit ec: ExecutionContext ): Future[SP.Started.type] = {
    doInitialize( resources ) match {
      case scalaz.Success( _ ) => outer.initializeContext( resources ) map { _ => SP.Started }
      case scalaz.Failure( exs ) => {
        exs foreach { ex => log.error( ex, "initialization failed for resources:[{}]", resources.mkString(", ") ) }
        Future.failed( exs.head )
      }
    }
  }

  def doInitialize( resources: Map[Symbol, Any] ): Valid[Done] = Done.successNel

  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.defaultStrategy

  override def receive: Actor.Receive = LoggingReceive { around( quiescent ) }

  implicit val ec = context.dispatcher

  val quiescent: Receive = {
    case SP.Load => {
      log.debug( "received Load...")
      val coordinator = sender()
      handleLoad() pipeTo coordinator
    }

    case SP.Initialize( resources ) => {
      log.debug( "received Initialize...")
      val coordinator = sender()
      handleInitialize( resources ) pipeTo coordinator
      context become LoggingReceive { around( nonfunctional orElse repository ) }
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
