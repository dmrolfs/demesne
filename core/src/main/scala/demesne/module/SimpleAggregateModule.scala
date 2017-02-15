package demesne.module

import scala.reflect._
import akka.actor.Props

import scalaz._
import Scalaz._
import shapeless._
import omnibus.commons.identifier.Identifying2
import omnibus.commons.builder._
import demesne._
import demesne.index.IndexSpecification
import demesne.repository.{AggregateRootProps, CommonClusteredRepository, CommonLocalRepository}

import scala.concurrent.duration.{Duration, FiniteDuration}


abstract class SimpleAggregateModule[S, I]( implicit override val identifying: Identifying2.Aux[S, I], val evState: ClassTag[S] )
  extends AggregateRootModule()( identifying ) { module =>
//  override def nextId: TryV[TID] = {
//    import scala.reflect._
//    val tidTag = classTag[TID]
//    identifying.nextId flatMap { nid =>
//      nid match {
//        case tidTag( t ) => t.right
//        case t => new ClassCastException( s"${t} id-type is not of type ${nid.id.getClass.getCanonicalName}" ).left
//      }
//    }
//  }

  def indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification]

  def aggregateRootPropsOp: AggregateRootProps
//todo why is this here?  def moduleProperties: Map[Symbol, Any] = Map.empty[Symbol, Any]

//  val evState: ClassTag[S] = the[ClassTag[S]]
//  val identifying: Identifying[S] = implicitly[Identifying[S]]

  def passivateTimeout: Duration
  def snapshotPeriod: Option[FiniteDuration]

  def startTask: demesne.StartTask = StartTask.empty( evState.runtimeClass.getCanonicalName )

  def environment: AggregateEnvironment

  class SimpleAggregateRootType(
    override val name: String,
    override val indexes: Seq[IndexSpecification],
    environment: AggregateEnvironment
  ) extends AggregateRootType {
    override val passivateTimeout: Duration = module.passivateTimeout
    override val snapshotPeriod: Option[FiniteDuration] = module.snapshotPeriod

    override def startTask: StartTask = module.startTask

//    override lazy val identifying: Identifying[_] = module.identifying

    override def repositoryProps( implicit model: DomainModel ): Props = {
      environment match {
        case ClusteredAggregate => CommonClusteredRepository.props( model, this, module.aggregateRootPropsOp )
        case LocalAggregate => CommonLocalRepository.props( model, this, module.aggregateRootPropsOp )
      }
    }

    override def canEqual( that: Any ): Boolean = that.isInstanceOf[SimpleAggregateRootType]

    override def toString: String = name + "SimpleAggregateRootType"
  }


  override val rootType: AggregateRootType = {
    new SimpleAggregateRootType( name = module.shardName, indexes = module.indexes, environment )
  }
}

object SimpleAggregateModule {
  def builderFor[S: ClassTag, I]( implicit identifying: Identifying2.Aux[S, I] ): BuilderFactory[S, I] = new BuilderFactory[S, I]

  class BuilderFactory[S: ClassTag, I]( implicit identifying: Identifying2.Aux[S, I] ) {
    type CC = SimpleAggregateModuleImpl[S, I]

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC] {
      object P {
//        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[S]].runtimeClass )
//        object Tag extends OptParam[Symbol]( identifying.idTag )
        object Props extends Param[AggregateRootProps]
        object PassivateTimeout extends OptParam[Duration]( AggregateRootType.DefaultPassivation )
        object SnapshotPeriod extends OptParam[Option[FiniteDuration]]( Some(AggregateRootType.DefaultSnapshotPeriod) )
        object StartTask extends OptParam[demesne.StartTask](
          demesne.StartTask.empty( s"start ${implicitly[ClassTag[S]].runtimeClass.getCanonicalName}" )
        )
        object Environment extends OptParam[AggregateEnvironment]( LocalAggregate )
        object Indexes extends OptParam[Seq[IndexSpecification]]( Seq.empty[IndexSpecification] )
      }

      override val gen = Generic[CC]
      override val fieldsContainer = createFieldsContainer(
//        P.Tag ::
        P.Props ::
        P.PassivateTimeout ::
        P.SnapshotPeriod ::
        P.StartTask ::
        P.Environment ::
        P.Indexes ::
        HNil
      )
    }
  }


  final case class SimpleAggregateModuleImpl[S, I](
//    override val aggregateIdTag: Symbol,
    override val aggregateRootPropsOp: AggregateRootProps,
    override val passivateTimeout: Duration,
    override val snapshotPeriod: Option[FiniteDuration],
    override val startTask: demesne.StartTask,
    override val environment: AggregateEnvironment,
    override val indexes: Seq[IndexSpecification]
  )(
    implicit override val identifying: Identifying2.Aux[S, I],
    evState: ClassTag[S]
  ) extends SimpleAggregateModule[S, I]()( identifying, evState ) with Equals { module =>
//    def bridgeIDClassTag[I: ClassTag]: ClassTag[I] = {
//      val lhs = implicitly[ClassTag[I]]
//      val rhs = identifying.evID
//      if ( lhs == rhs ) lhs
//      else throw new ClassCastException(
//        s"ID[${lhs.runtimeClass.getCanonicalName}] is equivalent to Identifying[T]#ID[${rhs.runtimeClass.getCanonicalName}]"
//      )
//    }

    override type ID = I
//    override def nextId: TryV[TID] = identifying.nextId

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[SimpleAggregateModuleImpl[S, I]]

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: SimpleAggregateModuleImpl[S, I] => {
        if ( this eq that ) true
        else {
          ( that.## == this.## ) &&
          ( that canEqual this ) &&
//          ( this.aggregateIdTag == that.aggregateIdTag ) &&
          ( this.indexes == that.indexes )
        }
      }

      case _ => false
    }

    override def hashCode: Int = {
      41 * (
        41 + indexes.##
      ) // + aggregateIdTag.##
    }
  }
}
