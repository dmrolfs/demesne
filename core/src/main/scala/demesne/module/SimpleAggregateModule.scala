package demesne.module

import scala.reflect.ClassTag
import akka.actor.Props
import demesne._
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModule[S] extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def aggregateRootPropsOp: AggregateRootProps

  def stateClass: Class[_] = implicitly[ClassTag[S]].runtimeClass
  implicit def evState: ClassTag[S]
  
  trait SimpleAggregateRootType extends AggregateRootType {
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
