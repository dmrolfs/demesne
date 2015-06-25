package demesne.module

import scala.reflect.ClassTag
import akka.actor.{ Actor, Props }
import akka.actor.Actor.Receive
import akka.event.LoggingReceive
import shapeless._
// import scalaz._, Scalaz._
import scalaz.{ Lens => _ }
import peds.archetype.domain.model.core.Entity
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }
import demesne._
import demesne.register.{ AggregateIndexSpec, StackableRegisterBusPublisher }
// import demesne.register.{ AggregateIndexSpec, Directive, StackableRegisterBusPublisher }
// import demesne.register.local.RegisterLocalAgent


trait BasicEntityModule[E <: Entity] extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  type Acceptance = AggregateStateSpecification.Acceptance[ActorState]

  def indexes: Seq[AggregateIndexSpec[_, _]] = Seq.empty[AggregateIndexSpec[_, _]]
  def acceptance: Acceptance
  def eventFor( state: ActorState ): PartialFunction[Command, Any]
  def activeCommand: Receive = Actor.emptyBehavior

  def entityClass: Class[_] = implicitly[ClassTag[E]].runtimeClass
  implicit def evEntity: ClassTag[E]
  
  type BasicEntity = E
  // implicit def evBasicEntity: ClassTag[BasicEntity]

  def idLens: Lens[BasicEntity, TID]
  def nameLens: Lens[BasicEntity, String]


  type Info = BasicEntity

  sealed trait BasicEntityProtocol

  case class Add( info: Info ) extends BasicEntityProtocol with Command {
    override def targetId: Add#TID = idLens.get( info )
  }

  case class Rename( override val targetId: Rename#TID, name: String ) extends BasicEntityProtocol with Command


  case class Added( info: Info ) extends BasicEntityProtocol with Event {
    override def sourceId: Added#TID = idLens.get( info )
  }

  case class Renamed( override val sourceId: Renamed#TID, oldName: String, newName: String ) 
  extends BasicEntityProtocol with Event


  override val aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = BasicEntityActor.props( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes
    }
  }


  type ActorState = BasicEntity
  // implicit val evActorState: ClassTag[ActorState] = ClassTag( entityClass )

  object ActorState {
    def apply( i: Info ): ActorState = {
      if ( i.isInstanceOf[ActorState] ) i.asInstanceOf[ActorState]
      else throw InfoStateIncompatibilityError[Info, ActorState]( i )
    }
  }


  implicit val stateSpecification = new AggregateStateSpecification[ActorState] {
    override def acceptance: Acceptance = module.acceptance orElse entityAcceptance
  
    val entityAcceptance: Acceptance = {
      case ( Added(info), state ) => ActorState( info )
      case ( Renamed(_, _, newName), state ) => module.nameLens.set( state )( newName )
    }
  }


  object BasicEntityActor {
    def props( model: DomainModel, meta: AggregateRootType ): Props = {
      Props( new BasicEntityActor( model, meta ) with StackableStreamPublisher with StackableRegisterBusPublisher )
    }
  }

  class BasicEntityActor( override val model: DomainModel, override val meta: AggregateRootType )
  extends AggregateRoot[ActorState] { publisher: EventPublisher =>
    import BasicEntityActor._

    override var state: ActorState = _

    override def receiveCommand: Receive = around( quiescent )

    def quiescent: Receive = LoggingReceive {
      case Add( info ) => {
        persistAsync( Added(info) ) { e => 
          acceptAndPublish( e )
          context become around( active )
        }
      }
    }

    def active: Receive = module.activeCommand orElse {
      case Rename( id, name ) => persistAsync( Renamed(id, nameLens.get(state), name) ) { e => acceptAndPublish( e ) }
    }
  }
}
