package demesne.module.entity

import scala.reflect._
import scala.concurrent.duration.{ Duration, FiniteDuration }
import akka.event.LoggingReceive
import cats.syntax.either._
import shapeless.{ Id => _, _ }
import omnibus.archetype.domain.model.core.Entity
import omnibus.akka.publish.EventPublisher
import omnibus.commons.builder.HasBuilder
import omnibus.identifier.Identifying
import omnibus.core.syntax.clazz._
import demesne.{ AggregateRoot, AggregateRootType }
import demesne.index.{ Directive, IndexSpecification }
import demesne.index.local.IndexLocalAgent
import demesne.module.{ AggregateEnvironment, SimpleAggregateModule }
import demesne.repository.AggregateRootProps

object EntityAggregateModule {
  type MakeIndexSpec = Function0[Seq[IndexSpecification]]
  val makeEmptyIndexSpec: MakeIndexSpec = () => Seq.empty[IndexSpecification]

  //  def makeSlugSpec[E <: Entity: Identifying: ClassTag, ID](
  //    idLens: Lens[E, E#TID],
  //    slugLens: Option[Lens[E, String]] = None,
  //    infoToEntity: PartialFunction[Any, Option[E]]
  //  )

  def makeSlugSpec[E <: Entity[E, ID]: Identifying: ClassTag, ID](
    idLens: Lens[E, E#TID],
    slugLens: Option[Lens[E, String]] = None,
    infoToEntity: PartialFunction[Any, Option[E]]
  ): IndexSpecification = {
    def label( entity: E ): String = slugLens map { _.get( entity ) } getOrElse {
      idLens.get( entity ).value.toString
    }

    val AddedType = classTag[EntityProtocol[E]#Added]
    val ResluggedType = classTag[EntityProtocol[E]#Reslugged]
    val DisabledType = classTag[EntityProtocol[E]#Disabled]
    val EnabledType = classTag[EntityProtocol[E]#Enabled]

    IndexLocalAgent.spec[String, E#TID, E#TID]( 'slug ) { // or 'activeSlug
      case AddedType( event ) => {
        scribe.debug( s"#TEST #SLUG: Index handling Added event: [${event}]" )
        val sid = event.sourceId //.value

        event.info
          .fold[Directive] {
            Directive.Ignore
          } { i =>
            triedToEntity[E, ID]( i )( infoToEntity )
              .fold {
                Directive.Record( sid.toString, sid )
              } { e =>
                val value = idLens.get( e )
                Directive.Record( label( e ), value /*.asInstanceOf[Id[E]]*/ )
              }
          }
      }

      case ResluggedType( event ) => {
        scribe.debug( s"#TEST #SLUG: Index handling Reslugged event: [${event}]" )
        Directive.ReviseKey( event.oldSlug, event.newSlug )
      }
      case DisabledType( event ) => {
        scribe.debug( s"#TEST #SLUG: Index handling Disabled event: [${event}]" )
        Directive.Withdraw( event.sourceId.value )
      }
      case EnabledType( event ) => {
        scribe.debug( s"#TEST #SLUG: Index handling Enabled event: [${event}]" )
        Directive.Record( event.slug, event.sourceId.value, event.sourceId.value )
      }
    }
//    (
//      ClassTag( classOf[String] ),
//      evID,
//      evID
//    )
  }

  def triedToEntity[E <: Entity[E, ID]: ClassTag, ID](
    from: Any
  )(
    toEntity: PartialFunction[Any, Option[E]]
  ): Option[E] = {
    if (!toEntity.isDefinedAt( from )) {
      scribe.warn(
        s"infoToEntity() is not defined for " +
        s"type:[${Option( from ) map { _.getClass.getCanonicalName } getOrElse "<null>"}] of " +
        s"from:[${Option( from ) map { _.toString } getOrElse ""}]"
      )

      None
    } else {
      Either
        .catchNonFatal { toEntity( from ) }
        .valueOr { ex =>
          scribe.error(
            s"failed to convert Added.info type[${from.getClass.getName}] " +
            s"to entity type[${the[ClassTag[E]].runtimeClass.getName}]",
            ex
          )

          None
        }
    }
  }

  def builderFor[E <: Entity[E, ID], ID, EP <: EntityProtocol[E]](
    implicit identifying: Identifying.Aux[E, ID],
    evState: ClassTag[E]
  ): BuilderFactory[E, ID, EP] = {
    new BuilderFactory[E, ID, EP]
  }

  class BuilderFactory[E <: Entity[E, ID], ID, EP <: EntityProtocol[E]](
    implicit val identifying: Identifying.Aux[E, ID],
    evState: ClassTag[E]
  ) {
    type CC = EntityAggregateModuleImpl

    def make: ModuleBuilder = new ModuleBuilder

    class ModuleBuilder extends HasBuilder[CC] {

      object P {
        object Props extends Param[AggregateRootProps]
        object PassivateTimeout extends OptParam[Duration]( AggregateRootType.DefaultPassivation )

        object SnapshotPeriod
            extends OptParam[Option[FiniteDuration]](
              Some( AggregateRootType.DefaultSnapshotPeriod )
            )
        object Protocol extends Param[EP]

        object StartTask
            extends OptParam[demesne.StartTask](
              demesne.StartTask.empty( s"start ${evState.runtimeClass.safeSimpleName}" )
            )

        object Environment
            extends OptParam[AggregateEnvironment.Resolver]( AggregateEnvironment.Resolver.local )
        object ClusterRole extends OptParam[Option[String]]( None )
        object Indexes extends OptParam[MakeIndexSpec]( makeEmptyIndexSpec )
        object IdLens extends Param[Lens[E, E#TID]]
        object NameLens extends Param[Lens[E, String]]
        object SlugLens extends OptParam[Option[Lens[E, String]]]( None )
        object IsActiveLens extends OptParam[Option[Lens[E, Boolean]]]( None )
      }

      import P.{ Props => PProps, _ }

      override val gen = Generic[CC]

      override val fieldsContainer = createFieldsContainer(
        PProps ::
        P.PassivateTimeout ::
        P.SnapshotPeriod ::
        Protocol ::
        P.StartTask ::
        Environment ::
        ClusterRole ::
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
      override val aggregateRootPropsOp: AggregateRootProps,
      override val passivateTimeout: Duration,
      override val snapshotPeriod: Option[FiniteDuration],
      override val protocol: EP,
      override val startTask: demesne.StartTask,
      override val environment: AggregateEnvironment.Resolver,
      override val clusterRole: Option[String],
      _indexes: MakeIndexSpec,
      override val idLens: Lens[E, E#TID],
      override val nameLens: Lens[E, String],
      override val slugLens: Option[Lens[E, String]],
      override val isActiveLens: Option[Lens[E, Boolean]]
    ) extends EntityAggregateModule[E, ID]
        with Equals {
//      override val evState: ClassTag[E] = the[ClassTag[E]]

      override type Protocol = EP
      override lazy val indexes: Seq[IndexSpecification] = _indexes()

      override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[EntityAggregateModuleImpl]

      override def equals( rhs: Any ): Boolean = rhs match {
        case that: EntityAggregateModuleImpl => {
          if (this eq that) true
          else {
            (that.## == this.##) &&
            (that canEqual this) &&
            (this.identifying.label == that.identifying.label)
          }
        }

        case _ => false
      }

      override def hashCode: Int = 41 * (41 + identifying.label.##)
    }
  }
}

abstract class EntityAggregateModule[E <: Entity[E, ID], ID](
  implicit override val identifying: Identifying.Aux[E, ID],
  override val evState: ClassTag[E]
) extends SimpleAggregateModule[E, ID] { module =>

  type Protocol <: EntityProtocol[E]
  val protocol: Protocol

  def idLens: Lens[E, E#TID]
  def nameLens: Lens[E, String]
  def slugLens: Option[Lens[E, String]] = None
  def isActiveLens: Option[Lens[E, Boolean]] = None

  def entityLabel( e: E ): String = slugLens map { _.get( e ) } getOrElse {
    idLens.get( e ).value.toString
  }

  def toEntity: PartialFunction[Any, Option[E]] = {
    case None                                   => None
    case Some( s ) if toEntity.isDefinedAt( s ) => toEntity( s )
    case Some( _ )                              => None
    case module.evState( s )                    => Option( s )
  }

  final def triedToEntity( from: Any ): Option[E] =
    Either.catchNonFatal { toEntity( from ) }.toOption.flatten

  class EntityAggregateRootType(
    name: String,
    indexes: Seq[IndexSpecification],
    environment: AggregateEnvironment.Resolver
  ) extends SimpleAggregateRootType( name, indexes, module.clusterRole, environment ) {
    override def canEqual( that: Any ): Boolean = that.isInstanceOf[EntityAggregateRootType]
  }

  override def rootType: AggregateRootType = {
    new EntityAggregateRootType(
      name = module.shardName,
      indexes = module.indexes,
      environment = module.environment
    )
  }

  abstract class EntityAggregateActor extends AggregateRoot[E] {
    publisher: AggregateRoot.Provider with EventPublisher =>
    override def acceptance: Acceptance = entityAcceptance

    def entityAcceptance: Acceptance = {
      case ( protocol.Added( _, info ), s ) => {
        preActivate()
        context become LoggingReceive { around( active ) }
        module.triedToEntity( info ) getOrElse s
      }
      case ( protocol.Renamed( _, _, newName ), s ) => module.nameLens.set( s )( newName )
      case ( protocol.Reslugged( _, _, newSlug ), s ) =>
        module.slugLens map { _.set( s )( newSlug ) } getOrElse s
      case ( _: protocol.Disabled, s ) => {
        preDisable()
        context become LoggingReceive { around( disabled ) }
        module.isActiveLens map { _.set( s )( false ) } getOrElse s
      }
      case ( _: protocol.Enabled, s ) => {
        preEnable()
        context become LoggingReceive { around( active ) }
        module.isActiveLens map { _.set( s )( true ) } getOrElse s
      }
    }

    def preActivate(): Unit = {}
    def preDisable(): Unit = {}
    def preEnable(): Unit = {}

    override def receiveCommand: Receive = LoggingReceive { around( quiescent ) }

    def quiescent: Receive = {
      case protocol.Add( targetId, info ) if targetId == aggregateId => {
        persist( protocol.Added( targetId, info ) ) { e =>
          acceptAndPublish( e )
        }
      }
    }

    def active: Receive = {
      case protocol.Rename( id, name ) => {
        persist( protocol.Renamed( id, module.nameLens.get( state ), name ) ) { e =>
          acceptAndPublish( e )
        }
      }

      case protocol.Reslug( id, slug ) if module.slugLens.isDefined => {
        persist( protocol.Reslugged( id, module.slugLens.get.get( state ), slug ) ) { e =>
          acceptAndPublish( e )
        }
      }

      case protocol.Disable( id )
          if module.isActiveLens.isDefined && id == module.idLens.get( state ) => {
        persist( protocol.Disabled( id, module.entityLabel( state ) ) ) { e =>
          acceptAndPublish( e )
        }
      }
    }

    def disabled: Receive = {
      case protocol.Enable( id )
          if module.isActiveLens.isDefined && id == module.idLens.get( state ) => {
        persist( protocol.Enabled( id, module.entityLabel( state ) ) ) { e =>
          acceptAndPublish( e )
        }
      }
    }
  }
}
