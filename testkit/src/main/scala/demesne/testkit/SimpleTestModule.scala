package demesne.testkit

import akka.actor.Props
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import omnibus.commons.ErrorOr
import omnibus.commons.identifier.Identifying
import demesne._
import demesne.index.{ IndexSpecification, StackableIndexBusPublisher }
import demesne.repository.CommonLocalRepository

abstract class SimpleTestModule[T, I0]( implicit override val identifying: Identifying.Aux[T, I0] )
    extends AggregateRootModule[T, I0] { module =>
  def name: String
  def indexes: Seq[IndexSpecification]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  def parseId( idstr: String ): TID = identifying.tidFromString( idstr )

  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name

      override type S = T
      override val identifying: Identifying[T] = module.identifying

      override def repositoryProps( implicit model: DomainModel ): Props = {
        CommonLocalRepository.props( model, this, SimpleTestActor.props( _, _ ) )
      }

      override def indexes: Seq[IndexSpecification] = module.indexes
    }
  }

  object SimpleTestActor {
    type State = Map[Symbol, Any]

    def props( model: DomainModel, rt: AggregateRootType ): Props = {
      Props(
        new SimpleTestActor( model, rt ) with StackableStreamPublisher
        with StackableIndexBusPublisher
      )
    }
  }

  implicit val stateIdentifying = new Identifying[SimpleTestActor.State] {
    override type ID = I0
    override val idTag: Symbol = module.identifying.idTag
    override def tidOf( s: SimpleTestActor.State ): TID = s( 'id ).asInstanceOf[TID]
    override def nextTID: ErrorOr[TID] = module.identifying.nextTID
    override def idFromString( idRep: String ): ID = module.identifying idFromString idRep
  }

  class SimpleTestActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State, ID]
      with AggregateRoot.Provider { outer: EventPublisher =>
    import SimpleTestActor._

    override var state: State = Map.empty[Symbol, Any]

    override val acceptance: Acceptance = module.acceptance

    override def receiveCommand: Receive = around {
      case command if module.eventFor( state ).isDefinedAt( command ) => {
        persist( module.eventFor( state )( command ) ) { event =>
          acceptAndPublish( event )
        }
      }
    }
  }
}
