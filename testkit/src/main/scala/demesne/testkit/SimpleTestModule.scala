package demesne.testkit

import akka.actor.Props
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import omnibus.core.ErrorOr
import omnibus.identifier.Identifying
import demesne._
import demesne.index.{ IndexSpecification, StackableIndexBusPublisher }
import demesne.repository.CommonLocalRepository

abstract class SimpleTestModule[S0, I0](
  implicit override val identifying: Identifying.Aux[S0, I0]
) extends AggregateRootModule[S0, I0] { module =>

  def name: String
  def indexes: Seq[IndexSpecification]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  def parseId( idstr: String ): TID = identifying fromString idstr

  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name

      override type S = S0
//      override val identifying: Identifying[T] = module.identifying

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

  implicit val stateIdentifying: Identifying.Aux[SimpleTestActor.State, module.identifying.ID] = {
    Identifying.pure(
      zeroValueFn = module.identifying.zeroValue,
      nextValueFn = () => module.identifying.nextValue,
      valueFromRepFn = module.identifying.valueFromRep( _: String )
    )
  }

  class SimpleTestActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State]
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
