package demesne.module.entity

import scala.reflect._
import akka.event.LoggingReceive
import scalaz.{-\/, \/, \/-}
import shapeless._
import com.typesafe.scalalogging.LazyLogging
import peds.archetype.domain.model.core.{Entity, EntityIdentifying}
import peds.akka.publish.EventPublisher
import peds.commons.builder.HasBuilder
import peds.commons.TryV
import demesne.{AggregateRoot, AggregateRootModule, AggregateRootType}
import demesne.index.{Directive, IndexSpecification}
import demesne.index.local.IndexLocalAgent
import demesne.module.{AggregateEnvironment, LocalAggregate, SimpleAggregateModule}
import demesne.repository.AggregateRootProps


object EntityAggregateModule extends LazyLogging {
  type MakeIndexSpec = Function0[Seq[IndexSpecification]]
  val makeEmptyIndexSpec: MakeIndexSpec = () => Seq.empty[IndexSpecification]

  def makeSlugSpec[E <: Entity : EntityIdentifying](
    idLens: Lens[E, E#TID],
    slugLens: Option[Lens[E, String]] = None
  )(
    infoToEntity: PartialFunction[Any, Option[E]]
  ): IndexSpecification = {
    def label( entity: E ): String = slugLens map { _.get( entity ) } getOrElse { idLens.get( entity ).get.toString }

    val AddedType = classTag[EntityProtocol[E#ID]#Added]
    val ResluggedType = classTag[EntityProtocol[E#ID]#Reslugged]
    val DisabledType = classTag[EntityProtocol[E#ID]#Disabled]
    val EnabledType = classTag[EntityProtocol[E#ID]#Enabled]

    IndexLocalAgent.spec[String, E#ID, E#ID]( 'slug ) { // or 'activeSlug
      case AddedType(event) => {
        logger.debug( "#TEST #SLUG: Index handling Added event: [{}]", event )
        val sid = event.sourceId.id

        event.info
        .map { i =>
          triedToEntity( i )( infoToEntity )
          .map { e => Directive.Record( label( e ), idLens.get( e ).id ) }
          .getOrElse { Directive.Record( sid.toString, sid ) }
        }
        .getOrElse { Directive.Ignore }
      }

      case ResluggedType(event) => {
        logger.info( "#TEST #SLUG: Index handling Reslugged event: [{}]", event )
        Directive.ReviseKey( event.oldSlug, event.newSlug )
      }
      case DisabledType(event) => {
        logger.info( "#TEST #SLUG: Index handling Disabled event: [{}]", event )
        Directive.Withdraw( event.sourceId.id )
      }
      case EnabledType(event) => {
        logger.info( "#TEST #SLUG: Index handling Enabled event: [{}]", event )
        Directive.Record( event.slug, event.sourceId.id, event.sourceId.id )
      }
    } (
      ClassTag( classOf[String] ),
      implicitly[EntityIdentifying[E]].evID,
      implicitly[EntityIdentifying[E]].evID
    )
  }

  def triedToEntity[E <: Entity : EntityIdentifying](
    from: Any
  )(
    toEntity: PartialFunction[Any, Option[E]]
  ): Option[E] = {
    if ( !toEntity.isDefinedAt( from ) ) {
      logger.warn(
        "infoToEntity() is not defined for type:[{}] of from:[{}]",
        Option( from ) map { _.getClass.getCanonicalName } getOrElse "<null>",
        Option( from ) map { _.toString } getOrElse ""
      )

      None
    } else {
      \/ fromTryCatchNonFatal { toEntity(from) } match {
        case \/-( to ) => to
        case -\/( ex ) => {
          logger.error(
            s"failed to convert Added.info type[${from.getClass.getCanonicalName}] " +
              s"to entity type[${implicitly[EntityIdentifying[E]].evEntity.runtimeClass.getCanonicalName}]",
            ex
          )

          None
        }
      }
    }
  }


  def builderFor[E <: Entity : ClassTag : EntityIdentifying, EP <: EntityProtocol[E#ID]]: BuilderFactory[E, EP] = {
    new BuilderFactory[E, EP]
  }

  class BuilderFactory[E <: Entity : ClassTag : EntityIdentifying, EP <: EntityProtocol[E#ID]] {
    type CC = EntityAggregateModuleImpl

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC]{
      object P {
        object Tag extends OptParam[Symbol]( AggregateRootModule tagify implicitly[ClassTag[E]].runtimeClass )
        object Props extends Param[AggregateRootProps]
        object Protocol extends Param[EP]
        object StartTask extends OptParam[demesne.StartTask](
          demesne.StartTask.empty( s"start ${implicitly[ClassTag[E]].runtimeClass.getCanonicalName}" )
        )
        object Environment extends OptParam[AggregateEnvironment]( LocalAggregate )
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
        Protocol ::
        P.StartTask ::
        Environment ::
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
      override val protocol: EP,
      override val startTask: demesne.StartTask,
      override val environment: AggregateEnvironment,
      _indexes: MakeIndexSpec,
      override val idLens: Lens[E, E#TID],
      override val nameLens: Lens[E, String],
      override val slugLens: Option[Lens[E, String]],
      override val isActiveLens: Option[Lens[E, Boolean]]
    ) extends EntityAggregateModule[E] with Equals {
      override val evState: ClassTag[E] = implicitly[ClassTag[E]]

      override type Protocol = EP
      override lazy val indexes: Seq[IndexSpecification] = _indexes()

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

  type Protocol <: EntityProtocol[ID]
  val protocol: Protocol

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

  class EntityAggregateRootType(
    name: String,
    indexes: Seq[IndexSpecification],
    environment: AggregateEnvironment
  ) extends SimpleAggregateRootType( name, indexes, environment ) {
    override def toString: String = name + "EntityAggregateRootType"
  }

  override def rootType: AggregateRootType = {
    new EntityAggregateRootType( name = module.shardName, indexes = module.indexes, environment )
  }


  abstract class EntityAggregateActor extends AggregateRoot[E, E#ID] { publisher: AggregateRoot.Provider with EventPublisher =>
    override def parseId( idstr: String ): TID = identifying.safeParseId[ID]( idstr )( identifying.evID )

    override def acceptance: Acceptance = entityAcceptance

    def entityAcceptance: Acceptance = {
      case (protocol.Added(_, info), s) => {
        preActivate()
        context become LoggingReceive{ around( active ) }
        module.triedToEntity( info ) getOrElse s
      }
      case (protocol.Renamed(_, _, newName), s ) => module.nameLens.set( s )( newName )
      case (protocol.Reslugged(_, _, newSlug), s ) => module.slugLens map { _.set( s )( newSlug ) } getOrElse s
      case (_: protocol.Disabled, s) => {
        preDisable()
        context become LoggingReceive { around( disabled ) }
        module.isActiveLens map { _.set( s )( false ) } getOrElse s
      }
      case (_: protocol.Enabled, s) => {
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
      case protocol.Add( targetId, info ) if targetId == aggregateId => {
        persist( protocol.Added(targetId, info) ) { e => acceptAndPublish( e ) }
      }
    }

    def active: Receive = {
      case protocol.Rename( id, name ) => {
        persist( protocol.Renamed(id, module.nameLens.get(state), name) ) { e => acceptAndPublish( e ) }
      }

      case protocol.Reslug( id, slug ) if module.slugLens.isDefined => {
        persist( protocol.Reslugged(id, module.slugLens.get.get(state), slug) ) { e => acceptAndPublish( e ) }
      }

      case protocol.Disable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( protocol.Disabled(id, module.entityLabel( state ) ) ) { e => acceptAndPublish( e ) }
      }
    }

    def disabled: Receive = {
      case protocol.Enable( id ) if module.isActiveLens.isDefined && id == module.idLens.get(state) => {
        persist( protocol.Enabled(id, module.entityLabel( state ) ) ) { e => acceptAndPublish( e ) }
      }
    }
  }
}
