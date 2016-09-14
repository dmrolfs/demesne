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
    get( rootName, id ) getOrElse { throw NoSuchAggregateRootError( name, rootTypes ) }
  }

  def get( rootType: AggregateRootType, id: Any ): Option[ActorRef] = get( rootType.name, id )

  def get( rootName: String, id: Any ): Option[ActorRef]

  def aggregateOf( rootType: AggregateRootType, id: Any ): ActorRef = apply( rootType.name, id )

  def aggregateOf( rootName: String, id: Any ): ActorRef = apply( rootName, id )

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

    override def get( rootName: String, id: Any ): Option[ActorRef] = trace.block(s"get($rootName, $id)") {
      aggregateRefs.get( rootName ) map { _.repositoryRef }
    }

    override def aggregateIndexFor[K, TID, V]( rootName: String, indexName: Symbol ): TryV[AggregateIndex[K, TID, V]] = trace.block(s"aggregateIndexFor($rootName, $indexName)") {
      val result = {
        for {
          RootTypeRef( _, rootType ) <- aggregateRefs get rootName
        _ = logger.debug( "TEST: rootType=[{}]", rootType)
          spec <- rootType.indexes find { _.name == indexName }
        _ = logger.debug( "TEST: spec=[{}]", spec)
        _ = logger.debug( "TEST: specAgents = [{}]", specAgents.mkString(", ") )
          agent <- specAgents get spec
        _ = logger.debug( "TEST: agent=[{}]", agent)
        } yield agent
      }

      result
      .map { _.mapTo[K, TID, V].right }
      .getOrElse { NoIndexForAggregateError( rootName, specAgents ).left[Index[K, TID, V]] }
    }

    def addAggregateType( rootType: AggregateRootType ): DomainModelCell = {
      supervisors foreach { _.repository ! StartChild( rootType.repositoryProps(this), rootType.repositoryName ) }
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
        logger.debug( "Budgets: index:[{}] agent:[{}] child-supervision:[{}]", indexBudget, agentBudget, repositoryBudget )

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
            logger.debug( "TEST: wip domain-cell:[{}]", cell)
            logger.debug( "Registering root-types[{}]@[{}] <- Completed", rootTypes.map{_.name}.mkString(", "), name )
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
      tiemout: Timeout
    ): Future[(String, RootTypeRef)] = {
      aggregateRefs.get( rootType.name )
      .map { ref =>
        logger.debug( "aggregate ref found for root-type:[{}]", rootType.name )
        Future successful (rootType.name -> ref)
      }
      .getOrElse {
        retrieveAggregate( rootType, supervisor )
        .map { ref =>
          logger.debug( "Registering root-type[{}]@[{}] - aggregate registry established ", rootType.name, name )
          rootType.name -> ref
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
        logger.debug( "RETRIEVING AGGREGATE from supervisor:[{}]", supervisor.path.name )
        ( supervisor ? StartChild(rootType.repositoryProps(this), rootType.repositoryName) )
        .mapTo[ChildStarted]
        .map { repoStarted =>
          logger.debug( "RETRIEVED AGGREGATE from supervisor:[{}]  child:[{}]", supervisor.path.name, repoStarted.child )
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
    ): Future[Seq[(IndexSpecification, IndexEnvelope)]] = {
      logger.debug( "establishing indexes for rootType:[{}] with supervisor:[{}]", rootType, supervisor.path.name )

      val result = rootType.indexes map { spec =>
        logger.debug( "TEST: establishing Index [{}] for rootTYpe:[{}]", spec.name, rootType )

        for {
          registration <- supervisor.ask( RegisterIndex(rootType, spec) )( indexBudget ).mapTo[IndexRegistered]
          ref = registration.agentRef
          envelope <- ref.ask( GetIndex )( agentBudget ).mapTo[IndexEnvelope]
        } yield {
          logger.debug(
            "TEST: established Index:[{}] for rootType:[{}] spec:[{}] agent:[{}]",
            spec.name, rootType, spec, envelope.payload.toString
          )
          logger.debug(
            "Registering root-type:[{}] spec->index:[{}] -> [{}]",
            rootType.name,
            spec.name,
            envelope.payload.toString
          )

          (spec -> envelope)
        }
      }

      Future sequence result
    }
  }


  final case class RootTypeRef private[DomainModel]( repositoryRef: ActorRef, rootType: AggregateRootType )


  final case class Supervisors private[demesne]( repository: ActorRef, index: ActorRef )


  final case class NoSuchAggregateRootError private[demesne]( name: String, rootTypes: Set[AggregateRootType] )
  extends NoSuchElementException(
    s"""DomainModel type registry does not have root-type:[${name}]; root-types:[${rootTypes.mkString("[",",","]")}]"""
  ) with DemesneError

  final case class NoIndexForAggregateError private[demesne](name: String, registry: Map[IndexSpecification, IndexEnvelope] )
  extends IllegalStateException(
    s"""DomainModel does not have index for root type [${name}]:: specAgentRegistry [${registry.mkString("[",",","]")}]"""
  ) with DemesneError
}









//object DomainModel {
//  type AggregateIndex[K, TID, V] = Index[K, TID, V]
//
//  trait Provider {
//    def model: DomainModel
//  }


//?  case class RootTypeRef( repositoryRef: ActorRef, rootType: AggregateRootType )


//  final case class DomainModelCell private[demesne](
//    override val name: String,
//    override val system: ActorSystem,
//    override val indexBus: IndexBus,
//    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType],
//    repositorySupervisor: Option[ActorRef] = None,
//    indexSupervisor: Option[ActorRef] = None
//  ) extends DomainModel with LazyLogging {
//    private val trace = Trace[DomainModelCell]
//
//    override def toString: String = {
//      s"""DomainModelCell(name=${name}, system=${system}, root-types=[${rootTypes.mkString(", ")}])"""
//    }

    // default dispatcher is okay since mutations are limited to bootstrap.
//    type AggregateRegistry = Map[String, RootTypeRef]
//    val aggregateRegistry: Agent[AggregateRegistry] = Agent( Map.empty[String, RootTypeRef] )( system.dispatcher )
//
//    type SpecAgents = Map[IndexSpecification, IndexEnvelope]
//    val specAgentRegistry: Agent[SpecAgents] = Agent( Map.empty[IndexSpecification, IndexEnvelope] )( system.dispatcher )

//    def addAggregateType( rootType: AggregateRootType ): DomainModelCell = trace.block(s"addAggregateType(${rootType.name})") {
//      repositorySupervisor foreach { supervisor =>
//        supervisor ! StartChild( rootType.repositoryProps(this), rootType.repositoryName )
//      }
//
//      this.copy( rootTypes = rootTypes + rootType )
//    }

//    override def get( rootName: String, id: Any ): Option[ActorRef] = trace.block( s"get($rootName, $id)" ) {
//      aggregateRegistry().get( rootName ).map { _.repositoryRef }
//    }

//    override def aggregateIndexFor[K, TID, V](rootName: String, registerName: Symbol ): TryV[AggregateIndex[K, TID, V]] = trace.block( s"aggregateIndexFor($rootName, $registerName)" ) {
//      trace( s"""aggregateRegistry = ${aggregateRegistry().mkString("[",",","]")}""")
//      trace( s"""specAgentRegistry=${specAgentRegistry().mkString("[",",","]")}""" )
//
//      val specRegistry = specAgentRegistry()
//
//      val result = for {
//        RootTypeRef(_, rootType) <- aggregateRegistry() get rootName
//        spec <- rootType.indexes find { _.name == registerName }
//        agent <- specRegistry get spec
//      } yield {
//        trace( s"""rootName=$rootName; rootType=$rootType""" )
//        trace( s"spec = ${spec}" )
//        trace( s"agent = ${agent}" )
//        agent
//      }
//
//      result
//      .map { _.mapTo[K, TID, V].right }
//      .getOrElse { NoIndexForAggregateError( rootName, specRegistry ).left[Index[K, TID, V]] }
//    }

//    def start()( implicit ec: ExecutionContext ): Future[Done] = trace.block( "start" ) {
//      val started = for {
//        repoSup <- repositorySupervisor
//        indexSup <- indexSupervisor
//      } yield {
//        val (registerIndexBudget, registerAgentBudget, startChildSupervisionBudget) = timeoutBudgets( Timeout(3.seconds) )
//
//        val tasks = rootTypes.toSeq map { rootType =>
//          logger.debug( "Registering root-type[{}]@[{}] -> Started", rootType.name, name )
//
//          val task = for {
//            reg <- establishIndexes( rootType, indexSup, registerIndexBudget, registerAgentBudget )
//            ag <- registerAggregate( rootType, repoSup, startChildSupervisionBudget )
//          } yield Done
//
//          task onComplete {
//            case Success(_) => {
//              logger.debug( "Registering root-type[{}]@[{}] <- Completed", rootType.name, name )
//            }
//
//            case Failure( ex ) => logger error s"failed to establish index for ${rootType}: ${ex}"
//          }
//
//          task
//        }
//
//        Future.sequence( tasks ) map { _ => Done }
//      }
//
//      started getOrElse {
//        Future.failed( new IllegalStateException("DomainModel must have repository and index supervisors set before starting") )
//      }
//    }

//    /**
//     * Returns the individual timeout budget components for aggregate type registration.
//     *
//     * @return (Index Index, Get Agent Index, Start Supervised Child)
//     */
//    private def timeoutBudgets( to: Timeout ): (Timeout, Timeout, Timeout) = {
//      val baseline = FiniteDuration( (0.9 * to.duration.toNanos).toLong, NANOSECONDS )
//      ( Timeout( baseline / 2 ), Timeout( baseline / 4 ), Timeout( baseline / 4 ) )
//    }

//    private def registerAggregate(
//      rootType: AggregateRootType,
//      supervisor: ActorRef,
//      budget: Timeout
//    )(
//      implicit ec: ExecutionContext
//    ): Future[Done] = trace.block( s"registerAggregate($rootType,_)" ) {
//      trace( s"index timeout budget = $budget" )
//
//      for {
//        exists <- aggregateRegistry.future() map { _ contains rootType.name }
//        if !exists
//        ref <- retrieveAggregate( rootType, supervisor, budget )
//        registry <- aggregateRegistry alter { _ + (rootType.name -> ref) }
//      } yield {
//        logger.debug( "Registering root-type[{}]@[{}] - aggregate registry established ", rootType.name, name )
//        Done
//      }
//    }

//    private def retrieveAggregate(
//      rootType: AggregateRootType,
//      supervisor: ActorRef,
//      budget: Timeout
//    )(
//      implicit ec: ExecutionContext
//    ): Future[RootTypeRef] = {
//      aggregateRegistry.future() flatMap { registry =>
//        registry
//        .get( rootType.name )
//        .map { Future successful _ }
//        .getOrElse {
//          logger.debug( "RETRIEVING AGGREGATE from supervisor:[{}]", supervisor.path.name )
//          ask( supervisor, StartChild(rootType.repositoryProps(this), rootType.repositoryName) )( budget )
//          .mapTo[ChildStarted]
//          .map { repoStarted =>
//            logger.debug( "RETRIEVED AGGREGATE from supervisor:[{}]  child:[{}]", supervisor.path.name, repoStarted.child )
//            RootTypeRef( repoStarted.child, rootType )
//          }
//        }
//      }
//    }

//    private def establishIndexes(
//      rootType: AggregateRootType,
//      supervisor: ActorRef,
//      registerIndexBudget: Timeout,
//      agentBudget: Timeout
//    )(
//      implicit ec: ExecutionContext
//    ): Future[Done] = trace.block( s"establishIndexes($rootType)" ) {
//      import akka.pattern.ask
//
//      val indexes = rootType.indexes map { s =>
//        for {
//          registration <- supervisor.ask( RegisterIndex( rootType, s ) )( registerIndexBudget ).mapTo[IndexRegistered]
//          agentRef = registration.agentRef
//          agentEnvelope <- agentRef.ask( GetIndex )( agentBudget ).mapTo[IndexEnvelope]
//          ar <- specAgentRegistry alter { m => m + (s -> agentEnvelope) }
//        } yield ar
//      }
//
//      Future.sequence( indexes ) map { r =>
//        logger.debug( "Registering root-type[{}]@[{}]: indexes[{}] established",
//          rootType.name,
//          name,
//          rootType.indexes.map{ _.toString }.mkString( "[", ",", "]" )
//        )
//
//        Done
//      }
//    }
//  }


//  final case class NoSuchAggregateRootError private[demesne]( name: String, rootTypes: Set[AggregateRootType] )
//  extends NoSuchElementException(
//    s"""DomainModel type registry does not have root-type:[${name}]; root-types:[${rootTypes.mkString("[",",","]")}]"""
//  ) with DemesneError


//  final case class NoIndexForAggregateError private[demesne]( name: String, registry: Map[IndexSpecification, IndexEnvelope] )
//  extends IllegalStateException(
//    s"""DomainModel does not have index for root type [${name}]:: specAgentRegistry [${registry.mkString("[",",","]")}]"""
//  ) with DemesneError
//}
