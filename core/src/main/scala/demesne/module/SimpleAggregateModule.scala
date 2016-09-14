package demesne.module

import scala.reflect._
import akka.actor.Props
import scalaz._
import Scalaz._
import shapeless._
import peds.commons.identifier.Identifying
import peds.commons.builder._
import peds.commons.util._
import peds.commons.log.Trace
import peds.commons.TryV
import demesne._
import demesne.index.IndexSpecification
import demesne.repository.{AggregateRootProps, CommonClusteredRepository, CommonLocalRepository}


abstract class SimpleAggregateModule[S: ClassTag : Identifying] extends AggregateRootModule { module =>
  override def nextId: TryV[TID] = {
    import scala.reflect._
    val tidTag = classTag[TID]
    identifying.nextId flatMap { nid =>
      nid match {
        case tidTag( t ) => t.right
        case t => new ClassCastException( s"${t} id-type is not of type ${nid.id.getClass.getCanonicalName}" ).left
      }
    }
  }

  def indexes: Seq[IndexSpecification] = Seq.empty[IndexSpecification]

  def aggregateRootPropsOp: AggregateRootProps
//todo why is this here?  def moduleProperties: Map[Symbol, Any] = Map.empty[Symbol, Any]

  val evState: ClassTag[S] = implicitly[ClassTag[S]]
  val identifying: Identifying[S] = implicitly[Identifying[S]]


  def environment: AggregateEnvironment

  class SimpleAggregateRootType(
    override val name: String,
    override val indexes: Seq[IndexSpecification],
    environment: AggregateEnvironment
  ) extends AggregateRootType {
    override def repositoryProps( implicit model: DomainModel ): Props = {
      environment match {
        case ClusteredAggregate => CommonClusteredRepository.props( model, this, module.aggregateRootPropsOp )
        case LocalAggregate => CommonLocalRepository.props( model, this, module.aggregateRootPropsOp )
      }
    }

    override def toString: String = name + "SimpleAggregateRootType"
  }


  override def rootType: AggregateRootType = {
    new SimpleAggregateRootType( name = module.shardName, indexes = module.indexes, environment )
  }
}

object SimpleAggregateModule {
  def builderFor[S: ClassTag : Identifying]: BuilderFactory[S] = new BuilderFactory[S]

  class BuilderFactory[S: ClassTag : Identifying] {
    type CC = SimpleAggregateModuleImpl[S]

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC] {
      object P {
        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[S]].runtimeClass )
        object Props extends Param[AggregateRootProps]
        object Environment extends OptParam[AggregateEnvironment]( LocalAggregate )
        object Indexes extends OptParam[Seq[IndexSpecification]]( Seq.empty[IndexSpecification] )
      }

      override val gen = Generic[CC]
      override val fieldsContainer = createFieldsContainer(
        P.Tag ::
        P.Props ::
        P.Environment ::
        P.Indexes ::
        HNil
      )
    }
  }


  final case class SimpleAggregateModuleImpl[S: ClassTag : Identifying](
    override val aggregateIdTag: Symbol,
    override val aggregateRootPropsOp: AggregateRootProps,
    override val environment: AggregateEnvironment,
    override val indexes: Seq[IndexSpecification]
  ) extends SimpleAggregateModule[S] with Equals { module =>
    override val trace: Trace[_] = Trace( s"SimpleAggregateModule[${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" )


    def bridgeIDClassTag[I: ClassTag]: ClassTag[I] = {
      val lhs = implicitly[ClassTag[I]]
      val rhs = identifying.evID
      if ( lhs == rhs ) lhs
      else throw new ClassCastException(
        s"ID[${lhs.runtimeClass.getCanonicalName}] is equivalent to Identifying[T]#ID[${rhs.runtimeClass.getCanonicalName}]"
      )
    }

    override type ID = identifying.ID
    override def nextId: TryV[TID] = identifying.nextId

    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[SimpleAggregateModuleImpl[S]]

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: SimpleAggregateModuleImpl[S] => {
        if ( this eq that ) true
        else {
          ( that.## == this.## ) &&
          ( that canEqual this ) &&
          ( this.aggregateIdTag == that.aggregateIdTag ) &&
          (this.indexes == that.indexes )
        }
      }

      case _ => false
    }

    override def hashCode: Int = {
      41 * (
        41 + aggregateIdTag.##
      ) + indexes.##
    }
  }
}
