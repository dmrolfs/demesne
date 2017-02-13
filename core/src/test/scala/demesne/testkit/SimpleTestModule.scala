package demesne.testkit

import scala.reflect.ClassTag
import akka.actor.Props
import demesne._
import demesne.index.{IndexSpecification, StackableIndexBusPublisher}
import demesne.repository.CommonLocalRepository
import omnibus.akka.publish.{EventPublisher, StackableStreamPublisher}
import omnibus.commons.identifier.Identifying
import omnibus.commons.log.Trace


abstract class SimpleTestModule[T: Identifying] extends AggregateRootModule { module =>
  def name: String
  def indexes: Seq[IndexSpecification]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  def parseId( idstr: String ): TID

  val evID: ClassTag[ID]

  private val trace = Trace[SimpleTestModule[T]]

  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name

      override lazy val identifying: Identifying[_] = implicitly[Identifying[T]]

      override def repositoryProps( implicit model: DomainModel ): Props = {
        CommonLocalRepository.props( model, this, SimpleTestActor.props(_, _) )
      }

      override def indexes: Seq[IndexSpecification] = module.indexes
    }
  }


  object SimpleTestActor {
    type State = Map[Symbol, Any]

    def props( model: DomainModel, rt: AggregateRootType ): Props = {
      Props( 
        new SimpleTestActor( model, rt ) with StackableStreamPublisher with StackableIndexBusPublisher
      )
    }
  }


  class SimpleTestActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State, ID] with AggregateRoot.Provider { outer: EventPublisher =>
    import SimpleTestActor._

    override var state: State = Map.empty[Symbol, Any]
    override val evState: ClassTag[State] = ClassTag( classOf[Map[Symbol, Any]] )

    override val acceptance: Acceptance = module.acceptance
    
    override def receiveCommand: Receive = around {
      case command if module.eventFor(state).isDefinedAt( command ) => {
        persist( module.eventFor(state)(command) ) { event => acceptAndPublish( event ) }
      }
    }
  }
}
