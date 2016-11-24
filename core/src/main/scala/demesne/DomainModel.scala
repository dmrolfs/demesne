package demesne

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import scalaz._
import Scalaz._
import com.typesafe.scalalogging.LazyLogging
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.commons.log.Trace
import peds.commons.TryV
import demesne.index._
import demesne.index.IndexSupervisor.{IndexRegistered, RegisterIndex}


abstract class DomainModel {
  import DomainModel.NoSuchAggregateRootError

  def name: String

  def system: ActorSystem

  def indexBus: IndexBus

  def apply( rootType: AggregateRootType, id: Any ): ActorRef = apply( rootType.name, id )

  def apply( rootName: String, id: Any ): ActorRef = {
    get( rootName, id ) getOrElse { throw NoSuchAggregateRootError( this, rootName ) }
  }

  def aggregateOf( rootType: AggregateRootType, id: Any ): ActorRef = apply( rootType.name, id )

  def aggregateOf( rootName: String, id: Any ): ActorRef = apply( rootName, id )

  def get( rootType: AggregateRootType, id: Any ): Option[ActorRef] = get( rootType.name, id )

  def get( rootName: String, id: Any ): Option[ActorRef]


  import DomainModel.AggregateIndex
  def aggregateIndexFor[K, TID, V]( rootType: AggregateRootType, name: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
    aggregateIndexFor[K, TID, V]( rootType.name, name )
  }

  def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]]

  def rootTypes: Set[AggregateRootType]
}

object DomainModel {
  trait Provider {
    def model: DomainModel
  }


  type AggregateIndex[K, TID, V] = Index[K, TID, V]


  final case class DomainModelCell private[demesne](
    key: Symbol,
    override val system: ActorSystem,
    override val indexBus: IndexBus = new IndexBus,
    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType],
    aggregateRefs: Map[String, RootTypeRef] = Map.empty[String, RootTypeRef],
    specAgents: Map[IndexSpecification, IndexEnvelope] = Map.empty[IndexSpecification, IndexEnvelope],
    supervisors: Option[Supervisors] = None
  ) extends DomainModel with LazyLogging {
    private val trace = Trace[DomainModelCell]

    override def toString: String = {
      s"""DomainModelCell(name=${name}, system=${system}, root-types=[${rootTypes.mkString(", ")}], """ +
      s"""aggregate-refs=[${aggregateRefs.mkString(", ")}]), spec-agents=[${specAgents.mkString(", ")}]"""
    }

    override def name: String = key.name

    override def get( rootName: String, id: Any ): Option[ActorRef] = trace.briefBlock(s"get($rootName, $id)") {
      aggregateRefs.get( rootName ) map { _.repositoryRef }
    }

    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = {
      val result = {
        for {
          RootTypeRef( _, rootType ) <- aggregateRefs get rootName
          spec <- rootType.indexes find { _.name == indexName }
          agent <- specAgents get spec
        } yield agent
      }

      result
      .map { _.mapTo[K, TID, V].right }
      .getOrElse { NoIndexForAggregateError( rootName, specAgents ).left[Index[K, TID, V]] }
    }

    def addAggregateType( rootType: AggregateRootType ): DomainModelCell = {
      implicit val timeout = Timeout( 5.seconds ) //todo consider better wait timeout
      supervisors foreach { supervisor =>
        val ref = ( supervisor.repository ? StartChild(rootType.repositoryProps(this), rootType.repositoryName) )
        scala.concurrent.Await.ready( ref, timeout.duration )
      }
      this.copy( rootTypes = rootTypes + rootType )
    }


    def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[DomainModelCell] = {
      logger.debug(
        "starting with supervisors:[{}] and rootTypes:[{}]",
        supervisors.map{ s => s"repository:[${s.repository.path.name}] index:[${s.index.path.name}]"},
        rootTypes.mkString(", ")
      )

      supervisors
      .map { s =>
        val (indexBudget, agentBudget, repositoryBudget) = timeoutBudgets( timeout )

        val indexes = rootTypes.toSeq map { rt => establishIndexes( rt, s.index, indexBudget, agentBudget )( ec ) }
        val aggregates = rootTypes map { rt => registerAggregate( rt, s.repository )( ec, repositoryBudget ) }

        val result = for {
          is <- Future.sequence( indexes ).map { _.flatten }
          as <- Future sequence aggregates
        } yield {
          this.copy( aggregateRefs = aggregateRefs ++ as, specAgents = specAgents ++ is )
        }

        result onComplete {
          case Success(cell) => {
            logger.debug( "Registering root-types[{}]@[{}] Completed", rootTypes.map{_.name}.mkString(", "), name )
          }

          case Failure( ex ) => logger error s"failed to start domain model: ${ex}"
        }

        result
      }
      .getOrElse {
        Future.failed( new IllegalStateException("DomainModel must have repository and index supervisors set before starting") )
      }
    }


    /**
      * Returns the individual timeout budget components for aggregate type registration.
      *
      * @return (Index Index, Get Agent Index, Start Supervised Child)
      */
    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout, Timeout) = {
      val baseline = FiniteDuration( (0.9 * to.duration.toNanos).toLong, NANOSECONDS )
      ( Timeout( baseline / 2 ), Timeout( baseline / 2 ), Timeout( baseline / 1 ) )
    }

    private def registerAggregate(
      rootType: AggregateRootType,
      supervisor: ActorRef
    )(
      implicit ec: ExecutionContext,
      timeout: Timeout
    ): Future[(String, RootTypeRef)] = {
      aggregateRefs
      .get( rootType.name )
      .map { ref =>
        logger.debug( "aggregate ref found for root-type:[{}]", rootType.name )
        Future successful (rootType.name -> ref)
      }
      .getOrElse {
        retrieveAggregate( rootType, supervisor )
        .map { ref =>
          logger.debug( "Registering root-type[{}]@[{}] - aggregate registry established ", rootType.name, name )
          ( rootType.name -> ref )
        }
      }
    }

    private def retrieveAggregate(
      rootType: AggregateRootType,
      supervisor: ActorRef
    )(
      implicit ec: ExecutionContext,
      to: Timeout
    ): Future[RootTypeRef] = {
      aggregateRefs
      .get( rootType.name )
      .map { Future successful _ }
      .getOrElse {
        ( supervisor ? StartChild(rootType.repositoryProps(this), rootType.repositoryName) ).mapTo[ChildStarted]
        .map { repoStarted => RootTypeRef( repoStarted.child, rootType ) }
      }
    }

    private def establishIndexes(
      rootType: AggregateRootType,
      supervisor: ActorRef,
      indexBudget: Timeout,
      agentBudget: Timeout
    )(
      implicit ec: ExecutionContext
    ): Future[Seq[(IndexSpecification, IndexEnvelope)]] = {
      logger.debug( "establishing indexes for rootType:[{}] with supervisor:[{}]", rootType, supervisor.path.name )

      val result = rootType.indexes map { spec =>
        for {
          registration <- supervisor.ask( RegisterIndex(rootType, spec) )( indexBudget ).mapTo[IndexRegistered]
          ref = registration.agentRef
          envelope <- ref.ask( GetIndex )( agentBudget ).mapTo[IndexEnvelope]
        } yield {
          logger.debug(
            "Registering root-type:[{}] name -> index:[{}] -> [{}]",
            rootType.name,
            spec.name,
            envelope.payload.toString
          )

          ( spec -> envelope )
        }
      }

      Future sequence result
    }
  }


  final case class RootTypeRef private[DomainModel]( repositoryRef: ActorRef, rootType: AggregateRootType )


  final case class Supervisors private[demesne]( repository: ActorRef, index: ActorRef )


  final case class NoSuchAggregateRootError private[demesne](
    model: DomainModel,
    rootName: String
  ) extends NoSuchElementException(
    s"DomainModel:[${model.name}] aggregate registry does not have aggregate root:[${rootName}]; " +
    s"""root-types:[${model.rootTypes.mkString(", ")}]"""
  ) with DemesneError

  final case class NoIndexForAggregateError private[demesne](name: String, registry: Map[IndexSpecification, IndexEnvelope] )
  extends IllegalStateException(
    s"""DomainModel does not have index for root type [${name}]:: specAgentRegistry [${registry.mkString("[",",","]")}]"""
  ) with DemesneError
}
