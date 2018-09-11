package demesne.testkit

import akka.actor.Props
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import omnibus.identifier.Identifying
import demesne._
import demesne.index.{ IndexSpecification, StackableIndexBusPublisher }
import demesne.repository.CommonLocalRepository

abstract class SimpleTestModule[S0, ID](
  implicit override val identifying: Identifying.Aux[S0, ID]
) extends AggregateRootModule[S0, ID] { module =>
  def name: String
  def indexes: Seq[IndexSpecification]
  def acceptance: AggregateRoot.Acceptance[SimpleTestActor.State]
  def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any]

  def parseId( idrep: String ): TID = identifying fromString idrep

  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.name

      override type S = S0
//      override val identifying: Identifying[S] = module.identifying

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

  implicit val stateIdentifying = {
    Identifying.pure[SimpleTestActor.State, module.identifying.ID](
      zeroValueFn = module.identifying.zeroValue,
      nextValueFn = () => module.identifying.nextValue,
      valueFromRepFn = module.identifying.valueFromRep( _: String )
    )
  }

  class SimpleTestActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SimpleTestActor.State, module.identifying.ID]
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
