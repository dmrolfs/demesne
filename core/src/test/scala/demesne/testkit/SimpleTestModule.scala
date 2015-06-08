package demesne.testkit

import akka.actor.Props
import demesne._
import demesne.register.AggregateIndexSpec
import peds.akka.publish.EventPublisher
import peds.commons.log.Trace


trait SimpleTestModule extends AggregateRootModule with CommonInitializeAggregateActorType { module =>
  def name: String
  def indexes: Seq[AggregateIndexSpec[_, _]]
  def acceptance( state: SimpleTestActor.State ): PartialFunction[Any, SimpleTestActor.State]
  def eventFor: PartialFunction[Any, Any]

  override val trace = Trace[SimpleTestModule]

  override def aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name
      override def aggregateRootProps( implicit model: DomainModel ): Props = SimpleTestActor.props( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
    }
  }


  implicit val stateSpecification = new AggregateStateSpecification[SimpleTestActor.State] {
    override def acceptance( state: SimpleTestActor.State ): Acceptance = module.acceptance( state )
  }

  object SimpleTestActor {
    def props( model: DomainModel, meta: AggregateRootType ): Props = Props( new SimpleTestActor( model, meta ) with EventPublisher )
    type State = Map[Symbol, Any]
  }

  class SimpleTestActor( model: DomainModel, override val meta: AggregateRootType ) extends AggregateRoot[SimpleTestActor.State] {
    outer: EventPublisher =>

    import SimpleTestActor._

    override val trace = Trace( "SimpleTestActor", log )

    override var state: State = Map.empty[Symbol, Any]

    override def receiveCommand: Receive = around {
      case command if module.eventFor.isDefinedAt( command ) => {
        persist( module.eventFor(command) ) { event => acceptAndPublish( event ) }
      }
    }
  }
}
