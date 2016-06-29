package demesne.testkit

import scala.reflect.ClassTag
import akka.actor.Props
import demesne._
import demesne.register.{AggregateIndexSpec, StackableRegisterBusPublisher}
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.commons.identifier.Identifying
import peds.commons.log.Trace


abstract class SimpleTestModule[T: Identifying] extends AggregateRootModule with CommonInitializeAggregateActorType { module =>
  def name: String
  def indexes: Seq[AggregateIndexSpec[_, _]]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  def parseId( idstr: String ): ID

  val evID: ClassTag[ID]

  override val trace = Trace[SimpleTestModule[T]]

  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name
      override def aggregateRootProps( implicit model: DomainModel ): Props = SimpleTestActor.props( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
    }
  }


  object SimpleTestActor {
    type State = Map[Symbol, Any]

    def props( model: DomainModel, rt: AggregateRootType ): Props = {
      Props( 
        new SimpleTestActor( model, rt ) with StackableStreamPublisher with StackableRegisterBusPublisher
      )
    }
  }


  class SimpleTestActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State, ID] { outer: EventPublisher =>
    import SimpleTestActor._

    override val trace = Trace( "SimpleTestActor", log )

    override def parseId( idstr: String ): ID = module.parseId( idstr )
    override var state: State = Map.empty[Symbol, Any]
    override val acceptance: Acceptance = module.acceptance
    
    override def receiveCommand: Receive = around {
      case command if module.eventFor(state).isDefinedAt( command ) => {
        persist( module.eventFor(state)(command) ) { event => acceptAndPublish( event ) }
      }
    }
  }
}
