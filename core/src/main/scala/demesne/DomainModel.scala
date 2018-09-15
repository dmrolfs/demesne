package demesne

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.Done
import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.sharding.ShardRegion
import akka.pattern.ask
import akka.util.Timeout
import cats.syntax.either._
import com.typesafe.config.Config
import omnibus.akka.supervision.IsolatedLifeCycleSupervisor.{ ChildStarted, StartChild }
import omnibus.core.ErrorOr
import demesne.index._
import demesne.index.IndexSupervisor.{ IndexRegistered, RegisterIndex }
import DomainModel.NoSuchAggregateRootError

abstract class DomainModel {
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

  def aggregateIndexFor[K, TID, V](
    rootType: AggregateRootType,
    name: Symbol
  ): ErrorOr[AggregateIndex[K, TID, V]] = {
    aggregateIndexFor[K, TID, V]( rootType.name, name )
  }

  def aggregateIndexFor[K, TID, V](
    rootName: String,
    indexName: Symbol
  ): ErrorOr[AggregateIndex[K, TID, V]]

  def rootTypes: Set[AggregateRootType]

  def configuration: Config
}

object DomainModel {

  trait Provider {
    def model: DomainModel
  }

  type AggregateIndex[K, TID, V] = Index[K, TID, V]

  final case class DomainModelCell private[demesne] (
    key: Symbol,
    override val system: ActorSystem,
    override val configuration: Config,
    override val indexBus: IndexBus = new IndexBus,
    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType],
    aggregateRefs: Map[String, RootTypeRef] = Map.empty[String, RootTypeRef],
    specAgents: Map[IndexSpecification, IndexEnvelope] =
      Map.empty[IndexSpecification, IndexEnvelope],
    supervisors: Option[Supervisors] = None
  ) extends DomainModel {
    override def toString: String = {
      s"""DomainModelCell(name=${name}, system=${system}, root-types=[${rootTypes.mkString( ", " )}], """ +
      s"""aggregate-refs=[${aggregateRefs.mkString( ", " )}]), spec-agents=[${specAgents.mkString(
        ", "
      )}]"""
    }

    override def name: String = key.name

    override def get( rootName: String, id: Any ): Option[ActorRef] =
      aggregateRefs.get( rootName ) map { _.repositoryRef }

    override def aggregateIndexFor[K, TID, V](
      rootName: String,
      indexName: Symbol
    ): ErrorOr[AggregateIndex[K, TID, V]] = {
      val result = {
        for {
          RootTypeRef( _, rootType ) <- aggregateRefs get rootName
          spec                       <- rootType.indexes find { _.name == indexName }
          agent                      <- specAgents get spec
        } yield agent
      }

      result
        .map { _.mapTo[K, TID, V].asRight }
        .getOrElse { NoIndexForAggregateError( rootName, specAgents ).asLeft }
    }

    def addAggregateType( rootType: AggregateRootType ): DomainModelCell = {
      implicit val timeout = Timeout( 5.seconds ) //todo consider better wait timeout
      supervisors foreach { supervisor =>
        val ref = (supervisor.repository ? StartChild(
          rootType.repositoryProps( this ),
          rootType.repositoryName
        ))
        scala.concurrent.Await.ready( ref, timeout.duration )
      }
      this.copy( rootTypes = rootTypes + rootType )
    }

    def start()( implicit ec: ExecutionContext, timeout: Timeout ): Future[DomainModelCell] = {
      scribe.debug(
        s"starting with supervisors:[${supervisors.map { s =>
          s"repository:[${s.repository.path.name}] index:[${s.index.path.name}]"
        }}] and rootTypes:[${rootTypes.mkString( ", " )}]"
      )

      supervisors
        .map { s =>
          val ( indexBudget, agentBudget, repositoryBudget ) = timeoutBudgets( timeout )

          val indexes = rootTypes.toSeq map { rt =>
            establishIndexes( rt, s.index, indexBudget, agentBudget )( ec )
          }
          val aggregates = rootTypes map { rt =>
            registerAggregate( rt, s.repository )( ec, repositoryBudget )
          }

          val result = for {
            is <- Future.sequence( indexes ).map { _.flatten }
            as <- Future sequence aggregates
          } yield {
            this.copy( aggregateRefs = aggregateRefs ++ as, specAgents = specAgents ++ is )
          }

          result onComplete {
            case Success( _ ) => {
              scribe.debug(
                s"Registering root-types[${rootTypes.map { _.name }.mkString( ", " )}]@[${name}] Completed"
              )
            }

            case Failure( ex ) => scribe.error( "failed to start domain model", ex )
          }

          result
        }
        .getOrElse {
          Future.failed(
            new IllegalStateException(
              "DomainModel must have repository and index supervisors set before starting"
            )
          )
        }
    }

    def shutdown: Future[Done] = {
      aggregateRefs.values foreach {
        case RootTypeRef( ref, rt ) =>
          scribe.info( s"shutting down aggregate: ${rt.name}" )
          ref ! ShardRegion.GracefulShutdown
      }

      Future successful Done
    }

    /**
      * Returns the individual timeout budget components for aggregate type registration.
      *
      * @return (Index Index, Get Agent Index, Start Supervised Child)
      */
    private def timeoutBudgets( to: Timeout ): ( Timeout, Timeout, Timeout ) = {
      val baseline = FiniteDuration( (0.9 * to.duration.toNanos).toLong, NANOSECONDS )
      ( Timeout( baseline / 2 ), Timeout( baseline / 2 ), Timeout( baseline / 1 ) )
    }

    private def registerAggregate(
      rootType: AggregateRootType,
      supervisor: ActorRef
    )(
      implicit ec: ExecutionContext,
      timeout: Timeout
    ): Future[( String, RootTypeRef )] = {
      aggregateRefs
        .get( rootType.name )
        .map { ref =>
          scribe.debug( s"aggregate ref found for root-type:[${rootType.name}]" )
          Future successful (rootType.name -> ref)
        }
        .getOrElse {
          retrieveAggregate( rootType, supervisor )
            .map { ref =>
              scribe.debug(
                s"Registering root-type[${rootType.name}]@[${name}] - aggregate registry established "
              )
              (rootType.name -> ref)
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
          (supervisor ? StartChild( rootType.repositoryProps( this ), rootType.repositoryName ))
            .mapTo[ChildStarted]
            .map { repoStarted =>
              RootTypeRef( repoStarted.child, rootType )
            }
        }
    }

    private def establishIndexes(
      rootType: AggregateRootType,
      supervisor: ActorRef,
      indexBudget: Timeout,
      agentBudget: Timeout
    )(
      implicit ec: ExecutionContext
    ): Future[Seq[( IndexSpecification, IndexEnvelope )]] = {
      if (rootType.indexes.isEmpty) {
        scribe.debug( s"no indexes for rootType:[${rootType}]" )
        Future successful Seq.empty[( IndexSpecification, IndexEnvelope )]
      } else {
        scribe.debug(
          s"establishing indexes for rootType:[${rootType}] with supervisor:[${supervisor.path.name}]"
        )

        val result = rootType.indexes map { spec =>
          for {
            registration <- supervisor
              .ask( RegisterIndex( rootType, spec ) )( indexBudget )
              .mapTo[IndexRegistered]
            ref = registration.agentRef
            envelope <- ref.ask( GetIndex )( agentBudget ).mapTo[IndexEnvelope]
          } yield {
            scribe.debug(
              s"Registering root-type:[${rootType.name}] name -> index:[${spec.name}] -> [${envelope.payload.toString}]"
            )

            (spec -> envelope)
          }
        }

        Future sequence result
      }
    }
  }

  final case class RootTypeRef private[DomainModel] (
    repositoryRef: ActorRef,
    rootType: AggregateRootType
  )

  final case class Supervisors private[demesne] ( repository: ActorRef, index: ActorRef )

  final case class NoSuchAggregateRootError private[demesne] (
    model: DomainModel,
    rootName: String
  ) extends NoSuchElementException(
        s"DomainModel:[${model.name}] aggregate registry does not have aggregate root:[${rootName}]; " +
        s"""root-types:[${model.rootTypes.mkString( ", " )}]"""
      )
      with DemesneError

  final case class NoIndexForAggregateError private[demesne] (
    name: String,
    registry: Map[IndexSpecification, IndexEnvelope]
  ) extends IllegalStateException(
        s"""DomainModel does not have index for root type [${name}]:: specAgentRegistry [${registry
          .mkString( "[", ",", "]" )}]"""
      )
      with DemesneError
}
