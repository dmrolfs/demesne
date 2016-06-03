package demesne.testkit

import akka.actor.Props
import demesne._
import demesne.register.{AggregateIndexSpec, RegisterBus, RegisterBusSubscription, StackableRegisterBusPublisher}
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.commons.log.Trace


trait SimpleTestModule extends AggregateRootModule with CommonInitializeAggregateActorType { module =>
  def name: String
  def indexes: Seq[AggregateIndexSpec[_, _]]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  override val trace = Trace[SimpleTestModule]

  override def aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name
      override def aggregateRootProps( implicit model: DomainModel ): Props = SimpleTestActor.props( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
    }
  }


  object SimpleTestActor {
    type State = Map[Symbol, Any]

    def props( model: DomainModel, meta: AggregateRootType ): Props = {
      Props( 
        new SimpleTestActor( model, meta ) with StackableStreamPublisher with StackableRegisterBusPublisher
      )
    }
  }

  class SimpleTestActor(
    override val model: DomainModel,
    override val meta: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State] {
    outer: EventPublisher =>

    import SimpleTestActor._

    override val trace = Trace( "SimpleTestActor", log )

    override var state: State = Map.empty[Symbol, Any]

    override val acceptance: Acceptance = module.acceptance
    
    override def receiveCommand: Receive = around {
      case command if module.eventFor(state).isDefinedAt( command ) => {
        persist( module.eventFor(state)(command) ) { event => acceptAndPublish( event ) }
      }
    }
  }
}
