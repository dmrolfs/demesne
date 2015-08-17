package demesne.module

import akka.actor.Props
import akka.event.LoggingReceive
import shapeless._
import peds.archetype.domain.model.core.Entity
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }
import demesne.{ AggregateRoot, AggregateRootType, DomainModel }
import demesne.register.{ AggregateIndexSpec, Directive, StackableRegisterBusPublisher }
import demesne.register.local.RegisterLocalAgent


trait EntityAggregateModule[E <: Entity] extends SimpleAggregateModule[E] { module =>
  def idLens: Lens[E, E#TID]
  def nameLens: Lens[E, String]
  def slugLens: Lens[E, String]
  def isActiveLens: Lens[E, Boolean]

  type Info = E

  def infoToEntity( from: Info ): E = from


  sealed trait EntityProtocol

  case class Add( info: Info ) extends Command with EntityProtocol {
    override def targetId: Add#TID = idLens.get( infoToEntity(info) ).asInstanceOf[Add#TID]
  }

  case class Rename( override val targetId: Rename#TID, name: String ) extends Command with EntityProtocol
  case class Reslug( override val targetId: Reslug#TID, slug: String ) extends Command with EntityProtocol
  case class Disable( override val targetId: Disable#TID ) extends Command with EntityProtocol
  case class Enable( override val targetId: Enable#TID ) extends Command with EntityProtocol


  case class Added( info: Info ) extends Event with EntityProtocol {
    override def sourceId: Added#TID = idLens.get( infoToEntity(info) ).asInstanceOf[Added#TID]
  }

  case class Renamed( override val sourceId: Renamed#TID, oldName: String, newName: String ) extends Event with EntityProtocol
  case class Reslugged( override val sourceId: Reslugged#TID, oldSlug: String, newSlug: String ) extends Event with EntityProtocol
  case class Disabled( override val sourceId: Disabled#TID, slug: String ) extends Event with EntityProtocol
  case class Enabled( override val sourceId: Enabled#TID, slug: String ) extends Event with EntityProtocol

  trait BasicEntityAggregateRootType extends SimpleAggregateRootType {
    override def toString: String = name + "BasicEntityAggregateRootType"
  }
  
  override val aggregateRootType: AggregateRootType = {
    new BasicEntityAggregateRootType {
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = {
        module.indexes ++ Seq(
          RegisterLocalAgent.spec[String, module.TID]( 'slug ) { // or 'activeSlug
            case Added( info ) => {
              val e = module.infoToEntity( info )
              Directive.Record( module.slugLens.get(e), module.idLens.get(e) )
            }

            case Reslugged( _, oldSlug, newSlug ) => Directive.Revise( oldSlug, newSlug )
            case Disabled( id, _ ) => Directive.Withdraw( id )
            case Enabled( id, slug ) => Directive.Record( slug, id )
          }
        )
      }
    }
  }


  // object EntityAggregateActor {
  //   def props( model: DomainModel, meta: AggregateRootType ): Props = {
  //     Props( new EntityAggregateActor( model, meta) with StackableStreamPublisher with StackableRegisterBusPublisher )
  //   }
  // }

  abstract class EntityAggregateActor extends AggregateRoot[E] { publisher: EventPublisher =>
    import AggregateRoot._

    override def acceptance: Acceptance[E] = entityAcceptance

    def entityAcceptance: Acceptance[E] = {
      case (Added(info), _) => module.infoToEntity( info )
      case (Renamed(_, _, newName), s ) => module.nameLens.set( s )( newName )
      case (Reslugged(_, _, newSlug), s ) => module.slugLens.set( s )( newSlug )
      case (_: Disabled, s) => module.isActiveLens.set( s )( false )
      case (_: Enabled, s) => module.isActiveLens.set( s )( true )
    }

    override def receiveCommand: Receive = around( quiescent )

    def quiescent: Receive = LoggingReceive {
      case Add( info ) => {
        persistAsync( Added(info) ) { e => 
          acceptAndPublish( e )
          context become around( active )
        }
      }
    }

    def active: Receive = LoggingReceive {
      case Rename( id, name ) => persistAsync( Renamed(id, module.nameLens.get(state), name) ) { e => acceptAndPublish( e ) }
      case Reslug( id, slug ) => persistAsync( Reslugged(id, module.slugLens.get(state), slug) ) { e => acceptAndPublish( e ) }
      case Disable( id ) if id == module.idLens.get(state) => persistAsync( Disabled(id, module.slugLens.get(state)) ) { e => 
        acceptAndPublish( e )
        context become around( disabled )
      }
    }

    def disabled: Receive = LoggingReceive {
      case Enable( id ) if id == module.idLens.get(state) => persistAsync( Enabled(id, module.slugLens.get(state)) ) { e =>
        acceptAndPublish( e )
        context become around( active )
      }
    }
  }
}

