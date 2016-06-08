package demesne.module

import scala.reflect.ClassTag
import akka.actor.Props
import akka.event.LoggingReceive
import shapeless._
import peds.archetype.domain.model.core.{Entity, Identifying}
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.commons.builder.HasBuilder
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{AggregateRoot, AggregateRootModule, AggregateRootType, DomainModel}
import demesne.register.{AggregateIndexSpec, Directive, StackableRegisterBusPublisher}
import demesne.register.local.RegisterLocalAgent
import peds.commons.TryV


object EntityAggregateModule {
  type MakeIndexSpec = Function0[Seq[AggregateIndexSpec[_,_]]]
  val makeEmptyIndexSpec = () => Seq.empty[AggregateIndexSpec[_,_]]

  def builderFor[E <: Entity : ClassTag]( implicit identifying: Identifying[E#ID] ): BuilderFactory[E] = new BuilderFactory[E]

  class BuilderFactory[E <: Entity : ClassTag]( implicit identifying: Identifying[E#ID] ) {
    type CC = EntityAggregateModuleImpl

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC]{
      object P {
        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[E]].runtimeClass )
        object Props extends Param[AggregateRootProps]
        object Indexes extends OptParam[MakeIndexSpec]( makeEmptyIndexSpec )
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


    // Impl CC required to be within BuilderFactory class in order to avoid the existential type issue preventing matching
    // of L <: HList inferred types in shapeless Generic[CC] and HasBuilder
    case class EntityAggregateModuleImpl(
      override val aggregateIdTag: Symbol,
      override val aggregateRootPropsOp: AggregateRootProps,
      _indexes: MakeIndexSpec,
      override val idLens: Lens[E, E#TID],
      override val nameLens: Lens[E, String],
      override val slugLens: Option[Lens[E, String]],
      override val isActiveLens: Option[Lens[E, Boolean]]
    )(
      implicit identifying: Identifying[E#ID]
    ) extends EntityAggregateModule[E] with Equals {
      override val trace: Trace[_] = Trace( s"EntityAggregateModule[${implicitly[ClassTag[E]].runtimeClass.safeSimpleName}]" )
      override val evState: ClassTag[E] = implicitly[ClassTag[E]]

      override def nextId: TryV[TID] = implicitly[Identifying[E#ID]].nextId map { tagId }

      override lazy val indexes: Seq[AggregateIndexSpec[_,_]] = _indexes()

      override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[EntityAggregateModuleImpl]

      override def equals( rhs: Any ): Boolean = rhs match {
        case that: EntityAggregateModuleImpl => {
          if ( this eq that ) true
          else {
            ( that.## == this.## ) &&
            ( that canEqual this ) &&
            ( this.aggregateIdTag == that.aggregateIdTag )
          }
        }

        case _ => false
      }

      override def hashCode: Int = {
        41 * (
          41 + aggregateIdTag.##
        ) 
      }
    }
  }
}

trait EntityAggregateModule[E <: Entity] extends SimpleAggregateModule[E, E#ID] { module =>
  def idLens: Lens[E, E#TID]
  def nameLens: Lens[E, String]
  def slugLens: Option[Lens[E, String]] = None
  def isActiveLens: Option[Lens[E, Boolean]] = None

  def getEntityKey( e: E ): String = slugLens map { _.get(e) } getOrElse { idLens.get(e).get.toString }

  type Info = E

  def infoToEntity( from: Info ): E = from

  trait Protocol {
    type Command = module.Command
    type Event = module.Event
    val Entity = EntityProtocol
  }

  object EntityProtocol {
    sealed trait EntityMessage

    case class Add( info: Info ) extends Command with EntityMessage {
      override def targetId: Add#TID = idLens.get( infoToEntity(info) ).asInstanceOf[Add#TID]
    }

    case class Rename( override val targetId: Rename#TID, name: String ) extends Command with EntityMessage
    case class Reslug( override val targetId: Reslug#TID, slug: String ) extends Command with EntityMessage
    case class Disable( override val targetId: Disable#TID ) extends Command with EntityMessage
    case class Enable( override val targetId: Enable#TID ) extends Command with EntityMessage


    case class Added( info: Info ) extends Event with EntityMessage {
      override def sourceId: Added#TID = idLens.get( infoToEntity(info) ).asInstanceOf[Added#TID]
    }

    case class Renamed( override val sourceId: Renamed#TID, oldName: String, newName: String ) extends Event with EntityMessage
    case class Reslugged( override val sourceId: Reslugged#TID, oldSlug: String, newSlug: String ) extends Event with EntityMessage
    case class Disabled( override val sourceId: Disabled#TID, slug: String ) extends Event with EntityMessage
    case class Enabled( override val sourceId: Enabled#TID, slug: String ) extends Event with EntityMessage
  }


  trait EntityAggregateRootType extends SimpleAggregateRootType {
    override def toString: String = name + "EntityAggregateRootType"
  }
  
  override val rootType: AggregateRootType = {
    new EntityAggregateRootType {
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = {
        module.indexes ++ Seq(
          RegisterLocalAgent.spec[String, module.TID]( 'slug ) { // or 'activeSlug
            case EntityProtocol.Added( info ) => {
              val e = module.infoToEntity( info )
              Directive.Record( getEntityKey(e), module.idLens.get(e) )
            }

            case EntityProtocol.Reslugged( _, oldSlug, newSlug ) => Directive.Revise( oldSlug, newSlug )
            case EntityProtocol.Disabled( id, _ ) => Directive.Withdraw( id )
            case EntityProtocol.Enabled( id, slug ) => Directive.Record( slug, id )
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
    import EntityProtocol._

    override def acceptance: Acceptance = entityAcceptance

    def entityAcceptance: Acceptance = {
      case (Added(info), _) => module.infoToEntity( info )
      case (Renamed(_, _, newName), s ) => module.nameLens.set( s )( newName )
      case (Reslugged(_, _, newSlug), s ) => module.slugLens map { _.set( s )( newSlug ) } getOrElse s
      case (_: Disabled, s) => module.isActiveLens map { _.set( s )( false ) } getOrElse s
      case (_: Enabled, s) => module.isActiveLens map { _.set( s )( true ) } getOrElse s
    }

    override def receiveCommand: Receive = LoggingReceive { around( quiescent ) }

    def quiescent: Receive = {
      case Add( info ) => {
        persist( Added(info) ) { e =>
          acceptAndPublish( e )
          context become LoggingReceive{ around( active ) }
        }
      }
    }

    def active: Receive = {
      case Rename( id, name ) => {
        persist( Renamed(id, module.nameLens.get(state), name) ) { e => acceptAndPublish( e ) }
      }
      case Reslug( id, slug ) if module.slugLens.isDefined => {
        persist( Reslugged(id, module.slugLens.get.get(state), slug) ) { e => acceptAndPublish( e ) }
      }
      case Disable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( Disabled(id, module.getEntityKey(state)) ) { e =>
          acceptAndPublish( e )
          context become LoggingReceive { around( disabled ) }
        }
      }
    }

    def disabled: Receive = LoggingReceive {
      case Enable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( Enabled(id, module.getEntityKey(state)) ) { e =>
          acceptAndPublish( e )
          context become LoggingReceive { around( active ) }
        }
      }
    }
  }
}

