package demesne.module

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect._
import akka.actor.Props
import akka.cluster.sharding.ClusterShardingSettings

import shapeless._
import omnibus.commons.identifier.Identifying
import omnibus.commons.builder._
import demesne._
import demesne.index.IndexSpecification
import demesne.repository.{ AggregateRootProps, CommonClusteredRepository, CommonLocalRepository }

abstract class SimpleAggregateModule[S0, I0](
  implicit override val identifying: Identifying.Aux[S0, I0],
  val evState: ClassTag[S0]
) extends AggregateRootModule()( identifying ) { module =>

  def indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification]

  def aggregateRootPropsOp: AggregateRootProps
//todo why is this here?  def moduleProperties: Map[Symbol, Any] = Map.empty[Symbol, Any]

  def passivateTimeout: Duration
  def snapshotPeriod: Option[FiniteDuration]

  def startTask: demesne.StartTask = StartTask.empty( evState.runtimeClass.getCanonicalName )

  def environment: AggregateEnvironment.Resolver

  def clusterRole: Option[String]

  class SimpleAggregateRootType(
    override val name: String,
    override val indexes: Seq[IndexSpecification],
    override val clusterRole: Option[String],
    environment: AggregateEnvironment.Resolver
  ) extends AggregateRootType {
    override val passivateTimeout: Duration = module.passivateTimeout
    override val snapshotPeriod: Option[FiniteDuration] = module.snapshotPeriod

    override def startTask: StartTask = module.startTask

    override type S = S0
    override val identifying: Identifying[S] = module.identifying

    override def repositoryProps( implicit model: DomainModel ): Props = {
      environment( model ) match {
        case ClusteredAggregate( toExtractEntityId, toExtractShardId ) => {
          CommonClusteredRepository.props(
            model,
            this,
            module.aggregateRootPropsOp
          )(
            adaptClusterShardSettings( ClusterShardingSettings( model.system ) ),
            toExtractEntityId( this ),
            toExtractShardId( this )
          )
        }

        case LocalAggregate =>
          CommonLocalRepository.props( model, this, module.aggregateRootPropsOp )
      }
    }

    override def canEqual( that: Any ): Boolean = that.isInstanceOf[SimpleAggregateRootType]
  }

  override def rootType: AggregateRootType = {
    new SimpleAggregateRootType(
      name = module.shardName,
      indexes = module.indexes,
      clusterRole = module.clusterRole,
      environment = module.environment
    )
  }
}

object SimpleAggregateModule {

  def builderFor[S: ClassTag, I](
    implicit identifying: Identifying.Aux[S, I]
  ): BuilderFactory[S, I] = new BuilderFactory[S, I]

  class BuilderFactory[S: ClassTag, I]( implicit identifying: Identifying.Aux[S, I] ) {
    type CC = SimpleAggregateModuleImpl[S, I]

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC] {

      object P {
        object Props extends Param[AggregateRootProps]
        object PassivateTimeout extends OptParam[Duration]( AggregateRootType.DefaultPassivation )

        object SnapshotPeriod
            extends OptParam[Option[FiniteDuration]](
              Some( AggregateRootType.DefaultSnapshotPeriod )
            )

        object StartTask
            extends OptParam[demesne.StartTask](
              demesne.StartTask.empty( s"start ${the[ClassTag[S]].runtimeClass.getCanonicalName}" )
            )

        object Environment
            extends OptParam[AggregateEnvironment.Resolver]( AggregateEnvironment.Resolver.local )
        object ClusterRole extends OptParam[Option[String]]( None )
        object Indexes extends OptParam[Seq[IndexSpecification]]( Seq.empty[IndexSpecification] )
      }

      override val gen = Generic[CC]
      override val fieldsContainer = createFieldsContainer(
        P.Props ::
        P.PassivateTimeout ::
        P.SnapshotPeriod ::
        P.StartTask ::
        P.Environment ::
        P.ClusterRole ::
        P.Indexes ::
        HNil
      )
    }
  }

  final case class SimpleAggregateModuleImpl[S, I](
    override val aggregateRootPropsOp: AggregateRootProps,
    override val passivateTimeout: Duration,
    override val snapshotPeriod: Option[FiniteDuration],
    override val startTask: demesne.StartTask,
    override val environment: AggregateEnvironment.Resolver,
    override val clusterRole: Option[String],
    override val indexes: Seq[IndexSpecification]
  )(
    implicit override val identifying: Identifying.Aux[S, I],
    evState: ClassTag[S]
  ) extends SimpleAggregateModule[S, I]()( identifying, evState )
      with Equals { module =>

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[SimpleAggregateModuleImpl[S, I]]

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: SimpleAggregateModuleImpl[S, I] => {
        if (this eq that) true
        else {
          (that.## == this.##) &&
          (that canEqual this) &&
          (this.indexes == that.indexes)
        }
      }

      case _ => false
    }

    override def hashCode: Int = {
      41 * (
        41 + indexes.##
      )
    }
  }
}
