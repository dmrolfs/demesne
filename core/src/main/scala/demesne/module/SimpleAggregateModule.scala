package demesne.module

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import akka.actor.Props
import scalaz._
import Scalaz._
import shapeless._
import peds.archetype.domain.model.core.Identifying
import peds.commons.builder._
import peds.commons.util._
import peds.commons.log.Trace
import peds.commons.{TryV, Valid}
import demesne._
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModule[S, I] extends AggregateRootModule[I] with InitializeAggregateRootClusterSharding { module =>
  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def aggregateRootPropsOp: AggregateRootProps
  def moduleProperties: Map[Symbol, Any] = Map.empty[Symbol, Any]

//  def stateClass: Class[_] = implicitly[ClassTag[S]].runtimeClass
  implicit def evState: ClassTag[S]
  
  trait SimpleAggregateRootType extends AggregateRootType {
    override def toString: String = name + "SimpleAggregateRootType"
  }

  override val rootType: AggregateRootType = {
    new SimpleAggregateRootType {
      override def name: String = module.shardName
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
    }
  }
}

object SimpleAggregateModule {
  def builderFor[S: ClassTag, I: ClassTag : Identifying]: BuilderFactory[S, I] = new BuilderFactory[S, I]

  class BuilderFactory[S: ClassTag, I: ClassTag : Identifying] {
    type CC = SimpleAggregateModuleImpl[S, I]

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


  final case class SimpleAggregateModuleImpl[S: ClassTag, I: ClassTag : Identifying](
    override val aggregateIdTag: Symbol,
    override val aggregateRootPropsOp: AggregateRootProps,
    override val indexes: Seq[AggregateIndexSpec[_,_]]
  ) extends SimpleAggregateModule[S, I] with Equals {
    override val trace: Trace[_] = Trace( s"SimpleAggregateModule[${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" )
    override val evState: ClassTag[S] = implicitly[ClassTag[S]]

    override def nextId: TryV[TID] = implicitly[Identifying[ID]].nextId map { tagId }

    var _props: Map[Symbol, Any] = Map()
    override def moduleProperties: Map[Symbol, Any] = _props

    override def initializer( 
      rootType: AggregateRootType, 
      model: DomainModel, 
      props: Map[Symbol, Any] 
    )( 
      implicit ec: ExecutionContext
    ) : Valid[Future[Unit]] = trace.block( "initializer" ) { Future.successful{ _props = props }.successNel }


    override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[SimpleAggregateModuleImpl[S, I]]

    override def equals( rhs: Any ): Boolean = rhs match {
      case that: SimpleAggregateModuleImpl[S, I] => {
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
