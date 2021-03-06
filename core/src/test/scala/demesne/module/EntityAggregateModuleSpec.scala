package demesne.module

import scala.concurrent.duration._
import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import org.scalatest.OptionValues
//import com.typesafe.config.Config
//import cats.syntax.either._
import shapeless._
import org.scalatest.Tag
import omnibus.archetype.domain.model.core.{ Entity, EntityLensProvider }
import omnibus.akka.envelope._
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import org.scalatest.concurrent.ScalaFutures
import demesne._
import demesne.index.{ IndexSpecification, StackableIndexBusPublisher }
import demesne.module.EntityAggregateModuleSpec.Foo
import demesne.testkit.AggregateRootSpec
import demesne.testkit.concurrent.CountDownFunction
import demesne.module.entity.{ EntityAggregateModule, EntityProtocol }
import omnibus.identifier.{ Identifying, ShortUUID }

object EntityAggregateModuleSpec {

  trait Foo extends Entity[Foo, ShortUUID] {
//    override type ID = ShortUUID

    def isActive: Boolean
    def f: Int
    def b: Double
    def z: String
  }

  object Foo extends EntityLensProvider[Foo, ShortUUID] {

    override implicit val identifying: Identifying.Aux[Foo, ShortUUID] = Identifying.byShortUuid

    override val idLens: Lens[Foo, Foo#TID] = new Lens[Foo, Foo#TID] {
      override def get( f: Foo ): Foo#TID = f.id
      override def set( f: Foo )( id: Foo#TID ): Foo = {
        FooImpl(
          id = id,
          name = f.name,
          slug = f.slug,
          isActive = f.isActive,
          f = f.f,
          b = f.b,
          z = f.z
        )
      }
    }

    override val nameLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.name
      override def set( f: Foo )( n: String ): Foo = {
        FooImpl(
          id = f.id,
          name = n,
          slug = f.slug,
          isActive = f.isActive,
          f = f.f,
          b = f.b,
          z = f.z
        )
      }
    }

    val slugLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.slug
      override def set( f: Foo )( s: String ): Foo = {
        FooImpl(
          id = f.id,
          name = f.name,
          slug = s,
          isActive = f.isActive,
          f = f.f,
          b = f.b,
          z = f.z
        )
      }
    }

    val isActiveLens: Lens[Foo, Boolean] = new Lens[Foo, Boolean] {
      override def get( f: Foo ): Boolean = f.isActive
      override def set( f: Foo )( a: Boolean ): Foo = {
        FooImpl( id = f.id, name = f.name, slug = f.slug, isActive = a, f = f.f, b = f.b, z = f.z )
      }
    }
  }

  final case class FooImpl(
    override val id: Foo#TID,
    override val name: String,
    override val slug: String,
    override val isActive: Boolean = true,
    override val f: Int = 0,
    override val b: Double = 0.0,
    override val z: String = ""
  ) extends Foo

  object Protocol extends EntityProtocol[Foo, Foo.identifying.ID] {
    case class Bar( targetId: Bar#TID, b: Int ) extends Command
    case class Barred( sourceId: Barred#TID, b: Int ) extends Event
  }

  object FooAggregateRoot {
    import demesne.index.{ Directive => D }
//    implicit val fi: Identifying.Aux[Foo, Foo#ID] = Foo.identifying
//    implicit val evID: ClassTag[Foo#ID] = classTag[ShortUUID]

    val myIndexes: () => Seq[IndexSpecification] = () => {
      val myIdLens: Lens[Foo, Foo#TID] = Foo.idLens
      Seq(
        EntityAggregateModule.makeSlugSpec[Foo, ShortUUID](
          idLens = myIdLens,
          slugLens = Some( Foo.slugLens ),
          infoToEntity = { case f: Foo => Some( f ) }
        ),
        demesne.index.local.IndexLocalAgent.spec[String, Foo#TID, Foo#TID]( 'name ) {
          case Protocol.Added( tid, info ) => {
            module
              .triedToEntity( info )
              .map { e =>
                D.Record( module.entityLabel( e ), module.idLens.get( e ) )
              }
              .getOrElse { D.Record( tid, tid ) }
          }

          case Protocol.Disabled( tid, _ ) => {
            scribe.debug( s"#TEST #SLUG: from Disabled Withdrawing: [${tid}]" )
            D.Withdraw( tid )
          }
          case Protocol.Enabled( tid, slug ) => D.Record( slug, tid )
        }
      )
    }

    val builderFactory: EntityAggregateModule.BuilderFactory[Foo, ShortUUID, Protocol.type] =
      EntityAggregateModule.builderFor[Foo, ShortUUID, Protocol.type]

    val module: EntityAggregateModule[Foo, ShortUUID] = {
      val b = builderFactory.make
      import b.P.{ Props => BProps, Protocol => BProtocol, _ }

      b.builder
//       .set( BTag, Foo.identifying.idTag )
        .set( BProps, FooActor.props( _: DomainModel, _: AggregateRootType ) )
        .set( BProtocol, Protocol )
        .set( Indexes, myIndexes )
        .set( IdLens, Foo.idLens )
        .set( NameLens, Foo.nameLens )
        .set( SlugLens, Some( Foo.slugLens ) )
        .set( IsActiveLens, Some( Foo.isActiveLens ) )
        .build()
    }

    object FooActor {

      def props( model: DomainModel, rt: AggregateRootType ): Props = {
        Props(
          new FooActor( model, rt ) with AggregateRoot.Provider with StackableStreamPublisher
          with StackableIndexBusPublisher
        )
      }
    }

    class FooActor( override val model: DomainModel, override val rootType: AggregateRootType )
        extends module.EntityAggregateActor
        with AggregateRoot.Provider { publisher: EventPublisher =>
      override var state: Foo = _
//      override val evState: ClassTag[Foo] = ClassTag( classOf[Foo] )

      override val active: Receive = super.active orElse {
        case Protocol.Bar( _, b ) => {
          sender() ! Protocol.Barred( state.id, b )
        }
      }
    }
  }
}

class EntityAggregateModuleSpec
    extends AggregateRootSpec[Foo, ShortUUID]
    with OptionValues
    with ScalaFutures {
  import EntityAggregateModuleSpec._

//  override type State = Foo
//  override type ID = ShortUUID

  override type Protocol = EntityAggregateModuleSpec.Protocol.type
  override val protocol: Protocol = EntityAggregateModuleSpec.Protocol

  override def createAkkaFixture(
    test: OneArgTest,
    system: ActorSystem,
    slug: String
  ): Fixture = {
    new TestFixture( slug, system )
  }

  override type Fixture = TestFixture

  class TestFixture( _slug: String, _system: ActorSystem )
      extends AggregateFixture( _slug, _system ) {
    override def nextId(): TID = Foo.identifying.next

    override val module: AggregateRootModule[Foo, ShortUUID] = FooAggregateRoot.module

    val rootType: AggregateRootType = module.rootType

    type SlugIndex =
      DomainModel.AggregateIndex[String, FooAggregateRoot.module.TID, FooAggregateRoot.module.ID]

    def slugIndex: SlugIndex = {
      model
        .aggregateIndexFor[String, FooAggregateRoot.module.TID, FooAggregateRoot.module.ID](
          rootType,
          'slug
        )
        .toOption
        .get
    }

    override def rootTypes: Set[AggregateRootType] = Set( rootType )
  }

  object ADD extends Tag( "add" )
  object UPDATE extends Tag( "update" )
  object GOOD extends Tag( "good" )

  "Module should" should {
    import FooAggregateRoot.{ module => Module }

    "build module" in { fixture: Fixture =>
      import fixture._

      val expected = FooAggregateRoot.builderFactory.EntityAggregateModuleImpl(
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props( _, _ ),
        passivateTimeout = AggregateRootType.DefaultPassivation,
        snapshotPeriod = Some( AggregateRootType.DefaultSnapshotPeriod ),
        protocol = Protocol,
        startTask = StartTask.empty( "expected" ),
        environment = AggregateEnvironment.Resolver.local,
        clusterRole = None,
        _indexes = FooAggregateRoot.myIndexes,
        idLens = Foo.idLens,
        nameLens = Foo.nameLens,
        slugLens = Some( Foo.slugLens ),
        isActiveLens = Some( Foo.isActiveLens )
      )

      scribe.info( s"ACTUAL = ${FooAggregateRoot.module}" )
      scribe.info( s"EXPECTED = ${expected}" )

      expected.canEqual( FooAggregateRoot.module ) must equal( true )
      expected.## must equal( FooAggregateRoot.module.## )
//      FooAggregateRoot.module.aggregateIdTag must equal( expected.aggregateIdTag )
      FooAggregateRoot.module.indexes must equal( expected.indexes )
      FooAggregateRoot.module must equal( expected )
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = Module.nextId
      val t = Module aggregateOf id
      t !+ Protocol.Rename( id, "foobar" )
      bus.expectNoMsg( 5.seconds.dilated )
    }

    "add foo" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId
//      val id = Foo.nextId
      val foo = Option( FooImpl( id, "foo1", "f1", true, 17, 3.14159, "zedster" ) )
      val f = Module aggregateOf id
      f !+ Protocol.Add( id, foo )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }
    }

    "update name" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId
      val f = Module aggregateOf id
      f !+ Protocol.Add( id, Option( FooImpl( id, "foo1", "f1", true, 17, 3.14159, "zedster" ) ) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ Protocol.Rename( id, "good-foo" )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo renamed" ) {
        case payload: Protocol.Renamed => {
          payload.sourceId mustBe id
          payload.oldName mustBe "foo1"
          payload.newName mustBe "good-foo"
        }
      }
    }

    "update slug" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId
      val f = Module aggregateOf id
      f !+ Protocol.Add( id, Option( FooImpl( id, "foo1", "f1", true, 17, 3.14159, "zedster" ) ) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ Protocol.Reslug( id, "gt" )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo slug changed" ) {
        case payload: Protocol.Reslugged => {
          payload.sourceId mustBe id
          payload.oldSlug mustBe "f1"
          payload.newSlug mustBe "gt"
        }
      }
    }

    "disable aggregate" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId
      val f = Module aggregateOf id
      f !+ Protocol.Add( id, Option( FooImpl( id, "foo1", "f1", true, 17, 3.14159, "zedster" ) ) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ Protocol.Disable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo disabled" ) {
        case payload: Protocol.Disabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }
    }

    "enable from disable aggregate" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId
      val f = Module aggregateOf id
      f !+ Protocol.Add( id, Option( FooImpl( id, "foo1", "f1", true, 17, 3.14159, "zedster" ) ) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ Protocol.Disable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo disabled" ) {
        case payload: Protocol.Disabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }

      f !+ Protocol.Enable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo enabled" ) {
        case payload: Protocol.Enabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }
    }

    "recorded in slug index" in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId
      val id = tid.value
      val f1 = Option( FooImpl( tid, "foo1", "f1", true, 17, 3.14159, "zedster" ) )

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ Protocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      val countDown = new CountDownFunction[String]
      countDown await 250.millis.dilated

      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result.value mustBe tid
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ).value mustBe tid
    }

    "bar command to force concrete protocol implementation" in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId
      val id = tid.value
      val f1 = Option( FooImpl( tid, "foo1", "f1", true, 17, 3.14159, "zedster" ) )

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ Protocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result.value mustBe tid
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ).value mustBe tid

      import akka.pattern.ask
//      implicit val timeout = akka.util.Timeout( 1.second )
      val bevt = (f ? Protocol.Bar( tid, 17 )).mapTo[Protocol.Barred]
      whenReady( bevt ) { e =>
        e.b mustBe 17
      }
    }

    "enablement actions translate in slug index" taggedAs WIP in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId
      val id = tid.value
      val f1 = Option( FooImpl( tid, "foo1", "f1", true, 17, 3.14159, "zedster" ) )

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ Protocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: Protocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result.value mustBe tid
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ).value mustBe tid

      f !+ Protocol.Disable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result mustBe None
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ) mustBe None

      f !+ Protocol.Enable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result.value mustBe tid
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ).value mustBe tid

      f !+ Protocol.Enable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result =>
        result.value mustBe tid
      }
      scribe.trace( s"""index:f1 = ${slugIndex.get( "f1" )}""" )
      slugIndex.get( "f1" ).value mustBe tid
    }
  }
}
