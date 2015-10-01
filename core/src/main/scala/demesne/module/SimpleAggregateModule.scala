package demesne.module

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import akka.actor.Props
import scalaz._, Scalaz._
import shapeless._
import peds.commons.builder._
import peds.commons.util._
import peds.commons.log.Trace
import peds.commons.V
import demesne._
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModule[S] extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def aggregateRootPropsOp: AggregateRootProps
  def props: Map[Symbol, Any] = Map.empty[Symbol, Any]

  def stateClass: Class[_] = implicitly[ClassTag[S]].runtimeClass
  implicit def evState: ClassTag[S]
  
  trait SimpleAggregateRootType extends AggregateRootType {
    override type ID = module.ID
    override def toString: String = name + "SimpleAggregateRootType"
  }

  override val aggregateRootType: AggregateRootType = {
    new SimpleAggregateRootType {
      override def name: String = module.shardName
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
    }
  }
}

object SimpleAggregateModule {
  def builderFor[S: ClassTag]: BuilderFactory[S] = new BuilderFactory[S]

  class BuilderFactory[S: ClassTag] {
    type CC = SimpleAggregateModuleImpl[S]

    // def make[L <: HList]( implicit g: Generic.Aux[CC, L] ): ModuleBuilder[L] = new ModuleBuilder[L]
    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC] {
      object P {
        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[S]].runtimeClass )
        object Props extends Param[AggregateRootProps]
        object Indexes extends OptParam[Seq[AggregateIndexSpec[_,_]]]( Seq.empty[AggregateIndexSpec[_,_]] )
      }

      override val gen = Generic[CC]
      override val fieldsContainer = createFieldsContainer( 
        P.Tag :: 
        P.Props :: 
        P.Indexes ::
        HNil 
      )
    }
  }


  final case class SimpleAggregateModuleImpl[S : ClassTag](
    override val aggregateIdTag: Symbol,
    override val aggregateRootPropsOp: AggregateRootProps,
    override val indexes: Seq[AggregateIndexSpec[_,_]]
  ) extends SimpleAggregateModule[S] with Equals {
    override val trace: Trace[_] = Trace( s"SimpleAggregateModule[${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" )
    override val evState: ClassTag[S] = implicitly[ClassTag[S]]

    var _props: Map[Symbol, Any] = Map()
    override def props: Map[Symbol, Any] = _props

    override def initializer( 
      rootType: AggregateRootType, 
      model: DomainModel, 
      props: Map[Symbol, Any] 
    )( 
      implicit ec: ExecutionContext
    ) : V[Future[Unit]] = trace.block( "initializer" ) { Future.successful{ _props = props }.successNel }


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
