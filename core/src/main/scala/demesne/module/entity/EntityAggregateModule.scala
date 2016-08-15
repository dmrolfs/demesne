package demesne.module.entity

import scala.reflect.ClassTag
import akka.actor.Props
import akka.event.LoggingReceive

import scalaz.{-\/, \/, \/-}
import shapeless._
import peds.archetype.domain.model.core.{Entity, EntityIdentifying}
import peds.akka.publish.EventPublisher
import peds.commons.builder.HasBuilder
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{AggregateRoot, AggregateRootModule, AggregateRootType, DomainModel}
import demesne.module.{AggregateRootProps, SimpleAggregateModule}
import demesne.module.entity.messages._
import demesne.index.{AggregateIndexSpec, Directive}
import demesne.index.local.IndexLocalAgent
import peds.commons.TryV


object EntityAggregateModule {
  type MakeIndexSpec = Function0[Seq[AggregateIndexSpec[_,_]]]
  val makeEmptyIndexSpec: MakeIndexSpec = () => Seq.empty[AggregateIndexSpec[_,_]]

  def builderFor[E <: Entity : ClassTag : EntityIdentifying]: BuilderFactory[E] = new BuilderFactory[E]

  class BuilderFactory[E <: Entity : ClassTag : EntityIdentifying] {
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
    ) extends EntityAggregateModule[E] with Equals {
      override val trace: Trace[_] = Trace( s"EntityAggregateModule[${implicitly[ClassTag[E]].runtimeClass.safeSimpleName}]" )
      override val evState: ClassTag[E] = implicitly[ClassTag[E]]

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

      override def hashCode: Int = 41 * ( 41 + aggregateIdTag.## )
    }
  }
}

abstract class EntityAggregateModule[E <: Entity : ClassTag : EntityIdentifying] extends SimpleAggregateModule[E] { module =>
  override val identifying: EntityIdentifying[E] = implicitly[EntityIdentifying[E]]

  override type ID = E#ID
  override def nextId: TryV[TID] = identifying.nextId

  def idLens: Lens[E, E#TID]
  def nameLens: Lens[E, String]
  def slugLens: Option[Lens[E, String]] = None
  def isActiveLens: Option[Lens[E, Boolean]] = None

  def entityLabel( e: E ): String = slugLens map { _.get( e ) } getOrElse { idLens.get( e ).get.toString }

  def toEntity: PartialFunction[Any, Option[E]] = {
    case None => None
    case Some( s ) if toEntity.isDefinedAt( s ) => toEntity( s )
    case Some( s ) => None
    case module.evState(s) => Option( s )
  }

  final def triedToEntity( from: Any ): Option[E] = {
    \/ fromTryCatchNonFatal { toEntity( from ) } match {
      case \/-(to) => to
      case -\/(ex) => {
        logger.error(
          s"failed to convert Added.info type[${from.getClass.getCanonicalName}] " +
            s"to entity type[${module.evState.runtimeClass.getCanonicalName}]",
          ex
        )

        throw ex
      }
    }
  }

  trait EntityAggregateRootType extends SimpleAggregateRootType {
    override def toString: String = name + "EntityAggregateRootType"
  }

  override def rootType: AggregateRootType = {
    new EntityAggregateRootType {
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = module.aggregateRootPropsOp( model, this )
      override def indexes: Seq[AggregateIndexSpec[_, _]] = module.indexes ++ Seq( makeSlugSpec )

      def makeSlugSpec: AggregateIndexSpec[_,_] = {
        IndexLocalAgent.spec[String, module.ID]( 'slug ) { // or 'activeSlug
          case Added( id, info ) => {
            module.triedToEntity( info )
            .map { e => Directive.Record( module.entityLabel( e ), module.idLens.get( e ).id ) }
            .getOrElse { Directive.Record( id, id ) }
          }

          case Reslugged( _, oldSlug, newSlug ) => Directive.Revise( oldSlug, newSlug )
          case Disabled( tid, _ ) => Directive.Withdraw( tid.id )
          case Enabled( tid, slug ) => Directive.Record( slug, tid.id )
        } (
          ClassTag( classOf[String] ),
          identifying.evID
         )
      }
    }
  }


  abstract class EntityAggregateActor extends AggregateRoot[E, E#ID] { publisher: EventPublisher =>
    override def parseId( idstr: String ): TID = {
//      identifying.tag( identifying.safeParseId[ID]( idstr )( identifying.evID ) )
      identifying.safeParseId[ID]( idstr )( identifying.evID )
    }


    override def acceptance: Acceptance = entityAcceptance

    def entityAcceptance: Acceptance = {
      case (Added(_, info), s) => {
        preActivate()
        context become LoggingReceive{ around( active ) }
        module.triedToEntity( info ) getOrElse s
      }
      case (Renamed(_, _, newName), s ) => module.nameLens.set( s )( newName )
      case (Reslugged(_, _, newSlug), s ) => module.slugLens map { _.set( s )( newSlug ) } getOrElse s
      case (_: Disabled, s) => {
        preDisable()
        context become LoggingReceive { around( disabled ) }
        module.isActiveLens map { _.set( s )( false ) } getOrElse s
      }
      case (_: Enabled, s) => {
        preEnable()
        context become LoggingReceive { around( active ) }
        module.isActiveLens map { _.set( s )( true ) } getOrElse s
      }
    }

    def preActivate(): Unit = { }
    def preDisable(): Unit = { }
    def preEnable(): Unit = { }

    override def receiveCommand: Receive = LoggingReceive { around( quiescent ) }

    def quiescent: Receive = {
      case Add( targetId, info ) if targetId == aggregateId => persist( Added(targetId, info) ) { e => acceptAndPublish( e ) }
    }

    def active: Receive = {
      case Rename( id, name ) => {
        persist( Renamed(id, module.nameLens.get(state), name) ) { e => acceptAndPublish( e ) }
      }
      case Reslug( id, slug ) if module.slugLens.isDefined => {
        persist( Reslugged(id, module.slugLens.get.get(state), slug) ) { e => acceptAndPublish( e ) }
      }
      case Disable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( Disabled(id, module.entityLabel( state ) ) ) { e => acceptAndPublish( e ) }
      }
    }

    def disabled: Receive = {
      case Enable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( Enabled(id, module.entityLabel( state ) ) ) { e => acceptAndPublish( e ) }
      }
    }
  }
}

