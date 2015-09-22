package demesne.module

import scala.reflect.ClassTag
import akka.actor.Props
import akka.event.LoggingReceive
import shapeless._
import peds.archetype.domain.model.core.Entity
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }
import peds.commons.builder.HasBuilder
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{ AggregateRoot, AggregateRootModule, AggregateRootType, DomainModel }
import demesne.register.{ AggregateIndexSpec, Directive, StackableRegisterBusPublisher }
import demesne.register.local.RegisterLocalAgent


object EntityAggregateModule {
  def builderFor[E <: Entity : ClassTag]: BuilderFactory[E] = new BuilderFactory[E]

  class BuilderFactory[E <: Entity : ClassTag] {
    type CC = EntityAggregateModuleImpl

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC]{
      object P {
        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[E]].runtimeClass )
        object Props extends Param[AggregateRootProps]
        object Indexes extends OptParam[Seq[AggregateIndexSpec[_,_]]]( Seq.empty[AggregateIndexSpec[_,_]] )
        object IdLens extends Param[Lens[E, E#TID]]
        object NameLens extends Param[Lens[E, String]]
        object SlugLens extends OptParam[Option[Lens[E, String]]]( None )
        object IsActiveLens extends OptParam[Option[Lens[E, Boolean]]]( None )
      }
      import P.{ Props => PProps, _ }

      override val gen = Generic[CC]

      override val fieldsContainer = createFieldsContainer( 
        Tag :: 
        PProps :: 
        Indexes :: 
        IdLens :: 
        NameLens :: 
        SlugLens :: 
        IsActiveLens :: 
        HNil 
      )
    }


    // Impl CC required to be within BuilderFactory class in order to avoid the existential type issue preventing  matching
    // of L <: HList inferred types in shapeless Generic[CC] and HasBuilder
    case class EntityAggregateModuleImpl(
      override val aggregateIdTag: Symbol,
      override val aggregateRootPropsOp: AggregateRootProps,
      override val indexes: Seq[AggregateIndexSpec[_,_]],
      override val idLens: Lens[E, E#TID],
      override val nameLens: Lens[E, String],
      override val slugLens: Option[Lens[E, String]],
      override val isActiveLens: Option[Lens[E, Boolean]]
    ) extends EntityAggregateModule[E] with Equals {
      override val trace: Trace[_] = Trace( s"EntityAggregateModule[${implicitly[ClassTag[E]].runtimeClass.safeSimpleName}]" )
      override val evState: ClassTag[E] = implicitly[ClassTag[E]]


      override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[EntityAggregateModuleImpl]

      override def equals( rhs: Any ): Boolean = rhs match {
        case that: EntityAggregateModuleImpl => {
          if ( this eq that ) true
          else {
            ( that.## == this.## ) &&
            ( that canEqual this ) &&
            ( this.aggregateIdTag == that.aggregateIdTag ) &&
            (this.indexes == that.indexes )
          }
        }

        case _ => false
      }

      override def hashCode: Int = {
        41 * (
          41 + aggregateIdTag.##
        ) + indexes.##
      }
    }
  }
}

trait EntityAggregateModule[E <: Entity] extends SimpleAggregateModule[E] { module =>
  def idLens: Lens[E, E#TID]
  def nameLens: Lens[E, String]
  def slugLens: Option[Lens[E, String]] = None
  def isActiveLens: Option[Lens[E, Boolean]] = None

  def getEntityKey( e: E ): String = slugLens map { _.get(e) } getOrElse { idLens.get(e).get.toString }

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

  trait EntityAggregateRootType extends SimpleAggregateRootType {
    override def toString: String = name + "EntityAggregateRootType"
  }
  
  override val aggregateRootType: AggregateRootType = {
    new EntityAggregateRootType {
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = {
        module.indexes ++ Seq(
          RegisterLocalAgent.spec[String, module.TID]( 'slug ) { // or 'activeSlug
            case Added( info ) => {
              val e = module.infoToEntity( info )
              Directive.Record( getEntityKey(e), module.idLens.get(e) )
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
      case (Reslugged(_, _, newSlug), s ) if module.slugLens.isDefined => module.slugLens.get.set( s )( newSlug )
      case (_: Disabled, s) if module.isActiveLens.isDefined => module.isActiveLens.get.set( s )( false )
      case (_: Enabled, s) if module.isActiveLens.isDefined => module.isActiveLens.get.set( s )( true )
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
      case Reslug( id, slug ) if module.slugLens.isDefined => {
        persistAsync( Reslugged(id, module.slugLens.get.get(state), slug) ) { e => acceptAndPublish( e ) }
      }
      case Disable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persistAsync( Disabled(id, module.getEntityKey(state)) ) { e => 
          acceptAndPublish( e )
          context become around( disabled )
        }
      }
    }

    def disabled: Receive = LoggingReceive {
      case Enable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persistAsync( Enabled(id, module.getEntityKey(state)) ) { e =>
          acceptAndPublish( e )
          context become around( active )
        }
      }
    }
  }
}

