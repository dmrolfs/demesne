package demesne.module

import scala.reflect.ClassTag
import akka.actor.Props
import shapeless._
import peds.commons.builder._
import peds.commons.util._
import peds.commons.log.Trace
import demesne._
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModule[S] extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def aggregateRootPropsOp: AggregateRootProps

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

  //   class ModuleBuilder[L <: HList]( implicit val g: Generic.Aux[CC, L] ) extends HasBuilder[CC] {
  //     object P {
  //       object Tag extends Param[Symbol]
  // //       object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[S]].runtimeClass )
  // //       object Props extends Param[AggregateRootProps]
  // //       object Indexes extends OptParam[Seq[AggregateIndexSpec[_,_]]]( Seq.empty[AggregateIndexSpec[_,_]] )
  //     }

  //     // more specific type signature need to solve for implicit resolution of Selector type class.
  //     // override val gen: Generic[SimpleAggregateModuleImpl[S]] { type Repr <: HList } = Generic[SimpleAggregateModuleImpl[S]]
  //     // override val fieldsContainer: FieldsContainer = createFieldsContainer( P.Tag :: P.Props :: P.Indexes :: HNil )
  //     override val gen = Generic[CC]
  //     override val fieldsContainer = createFieldsContainer( 
  //       P.Tag :: 
  // //       P.Props :: 
  // //       P.Indexes :: 
  //       HNil 
  //     )
  //   }
  }


  // I'd prefer to define this class within SimpleAggregateModule object, but that results in an inability for the
  // compiler to find the implicit HasBuilder#ParamValueExtractor.
  final case class SimpleAggregateModuleImpl[S : ClassTag](
    override val aggregateIdTag: Symbol,
    override val aggregateRootPropsOp: AggregateRootProps,
    override val indexes: Seq[AggregateIndexSpec[_,_]]
  ) extends SimpleAggregateModule[S] with Equals {
    override val trace: Trace[_] = Trace( s"SimpleAggregateModule[${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" )
    override val evState: ClassTag[S] = implicitly[ClassTag[S]]

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
