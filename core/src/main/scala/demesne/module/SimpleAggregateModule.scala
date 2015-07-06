package demesne.module

import scala.reflect.ClassTag
import akka.actor.{ Actor, Props }
import akka.actor.Actor.Receive
import akka.agent.Agent
import akka.event.LoggingReceive
import shapeless._
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }
import demesne._
import demesne.register.{ AggregateIndexSpec, StackableRegisterBusPublisher }

import scala.language.higherKinds


trait SimpleAggregateModule[A] extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  type Acceptance = AggregateStateSpecification.Acceptance[A]

  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def acceptance: Acceptance
  def statefulReceiveCommand: StatefulReceive[A] = _ => _ => Actor.emptyBehavior

  def aggregateClass: Class[_] = implicitly[ClassTag[A]].runtimeClass
  implicit def evAggregate: ClassTag[A]
  
  override val aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override def name: String = module.shardName
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
      override def aggregateRootProps( implicit model: DomainModel ): Props = SimpleAggregateModuleActor.props( model, this )
    }
  }

  implicit val stateSpecification = new AggregateStateSpecification[A] {
    override def acceptance: Acceptance = module.acceptance
  }


  object SimpleAggregateModuleActor {
    def props( model: DomainModel, meta: AggregateRootType ): Props = {
      Props( new SimpleAggregateModuleActor( model, meta ) with StackableStreamPublisher with StackableRegisterBusPublisher )
    }
  }

  class SimpleAggregateModuleActor( override val model: DomainModel, override val meta: AggregateRootType )
  extends AggregateRoot[A] { publisher: EventPublisher =>

    val stateAgent: Agent[A] = Agent.apply[A]( null.asInstanceOf[A] )( model.system.dispatcher )
    override def state: A = stateAgent.get
    override def state_=( newState: A ): Unit = stateAgent send newState

    override def receiveCommand: Receive = around( module.statefulReceiveCommand(this)(stateAgent) )
  }
}
