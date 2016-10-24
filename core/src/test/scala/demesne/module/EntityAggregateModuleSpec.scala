package demesne.module

import scala.concurrent.duration._
import scala.reflect._
import akka.actor.Props
import akka.testkit._

import scalaz.Scalaz._
import shapeless._
import org.scalatest.Tag
import peds.archetype.domain.model.core.{Entity, EntityIdentifying, EntityLensProvider}
import peds.akka.envelope._
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.commons.log.Trace
import peds.commons.identifier._
import peds.commons.TryV
import org.scalatest.concurrent.ScalaFutures
import demesne._
import demesne.module.entity.{messages => EntityProtocol}
import demesne.index.{IndexSpecification, StackableIndexBusPublisher}
import demesne.testkit.AggregateRootSpec
import demesne.testkit.concurrent.CountDownFunction
import demesne.module.entity.EntityAggregateModule


object EntityAggregateModuleSpec {
  object Protocol extends AggregateProtocol[Foo#ID] {
    case class Bar( targetId: Bar#TID, b: Int ) extends Command
    case class Barred( sourceId: Barred#TID, b: Int ) extends Event
  }


  trait Foo extends Entity {
    override type ID = ShortUUID
    override val evID: ClassTag[ID] = classTag[ShortUUID]
    override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]

    def isActive: Boolean
    def f: Int
    def b: Double
    def z: String
  }

  object Foo extends EntityLensProvider[Foo] {
    implicit val identifying: EntityIdentifying[Foo] = new EntityIdentifying[Foo] {
      override val evEntity: ClassTag[Foo] = classTag[Foo]
      override type ID = ShortUUID
      override lazy val evID: ClassTag[ID] = classTag[ShortUUID]
      override lazy val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]
      override def nextId: TryV[TID] = tag( ShortUUID() ).right
      override def fromString( idstr: String ): ID = ShortUUID( idstr )
    }


    override val idLens: Lens[Foo, Foo#TID] = new Lens[Foo,  Foo#TID] {
      override def get( f: Foo ): Foo#TID = f.id
      override def set( f: Foo )( id: Foo#TID ): Foo = {
        FooImpl( id = id, name = f.name, slug = f.slug, isActive = f.isActive, f = f.f, b = f.b, z = f.z )
      }
    }

    override val nameLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.name
      override def set( f: Foo )( n: String ): Foo = {
        FooImpl( id = f.id, name = n, slug = f.slug, isActive = f.isActive, f = f.f, b = f.b, z = f.z )
      }
    }

    val slugLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.slug
      override def set( f: Foo )( s: String ): Foo = {
        FooImpl( id = f.id, name = f.name, slug = s, isActive = f.isActive, f = f.f, b = f.b, z = f.z )
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


  object FooAggregateRoot {
    import demesne.index.{ Directive => D }

    val myIndexes: () => Seq[IndexSpecification] = () => trace.briefBlock( "myIndexes" ) {
      Seq(
        EntityAggregateModule.makeSlugSpec[Foo]( Foo.idLens, Some(Foo.slugLens) ){
          case f: Foo => Some( f )
        },
        demesne.index.local.IndexLocalAgent.spec[String, Foo#TID, Foo#TID]( 'name ) {
          case EntityProtocol.Added( id, info ) => {
            module.triedToEntity( info )
            .map { e => D.Record( module.entityLabel(e), module.idLens.get(e) ) }
            .getOrElse { D.Record(id, id) }
          }

           case EntityProtocol.Disabled( id, _ ) => D.Withdraw( id )
           case EntityProtocol.Enabled( id, slug ) => D.Record( slug, id )
        }
      )
    }

    val trace = Trace[FooAggregateRoot.type]
    val builderFactory: EntityAggregateModule.BuilderFactory[Foo] = EntityAggregateModule.builderFor[Foo]
    val module: EntityAggregateModule[Foo] = trace.block( "foo-module" ) {
      val b = builderFactory.make
      import b.P.{ Tag => BTag, Props => BProps, _ }

      b.builder
       .set( BTag, Foo.identifying.idTag )
       .set( BProps, FooActor.props(_,_) )
       .set( Indexes, myIndexes )
       .set( IdLens, Foo.idLens )
       .set( NameLens, Foo.nameLens )
       .set( SlugLens, Some(Foo.slugLens) )
       .set( IsActiveLens, Some(Foo.isActiveLens) )
       .build()
    }


    object FooActor {
      def props( model: DomainModel, rt: AggregateRootType ): Props = {
        Props( new FooActor(model, rt) with AggregateRoot.Provider with StackableStreamPublisher with StackableIndexBusPublisher )
      }
    }

    class FooActor( override val model: DomainModel, override val rootType: AggregateRootType )
    extends module.EntityAggregateActor with AggregateRoot.Provider { publisher: EventPublisher =>
      override var state: Foo = _
      override val evState: ClassTag[Foo] = ClassTag( classOf[Foo] )

      override val active: Receive = super.active orElse {
        case Protocol.Bar( _, b ) => {
          sender() ! Protocol.Barred( state.id, b )
        }
      }
    }
  }
}


class EntityAggregateModuleSpec extends AggregateRootSpec[EntityAggregateModuleSpec] with ScalaFutures {
  import EntityAggregateModuleSpec._

  private val trace = Trace[EntityAggregateModuleSpec]

  override type ID = ShortUUID

  override type Protocol = EntityAggregateModuleSpec.Protocol.type
  override val protocol: Protocol = EntityAggregateModuleSpec.Protocol

  override type Fixture = TestFixture

  class TestFixture extends AggregateFixture {
    private val trace = Trace[TestFixture]
    override def nextId(): TID = Foo.identifying.safeNextId

    override val module: AggregateRootModule = FooAggregateRoot.module

    val rootType: AggregateRootType = module.rootType

    type SlugIndex = DomainModel.AggregateIndex[String, FooAggregateRoot.module.TID, FooAggregateRoot.module.TID]
    def slugIndex: SlugIndex = {
      model.aggregateIndexFor[String, FooAggregateRoot.module.TID, FooAggregateRoot.module.TID]( rootType, 'slug ).toOption.get
    }

    override def rootTypes: Set[AggregateRootType] = Set( rootType )
  }

  override def createAkkaFixture( test: OneArgTest ): Fixture = new TestFixture

  object ADD extends Tag( "add" )
  object UPDATE extends Tag( "update" )
  object GOOD extends Tag( "good" )

  "Module should" should {
    import FooAggregateRoot.{ module => Module }

    "build module" in { fixture: Fixture =>
      import fixture._

      val expected = FooAggregateRoot.builderFactory.EntityAggregateModuleImpl(
        aggregateIdTag = Foo.identifying.idTag,
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props(_,_),
        environment = LocalAggregate,
        _indexes = FooAggregateRoot.myIndexes,
        idLens = Foo.idLens,
        nameLens = Foo.nameLens,
        slugLens = Some(Foo.slugLens),
        isActiveLens = Some(Foo.isActiveLens)
      )

      logger.info( "ACTUAL = {}", FooAggregateRoot.module)
      logger.info( "EXPECTED = {}", expected)

      expected.canEqual( FooAggregateRoot.module ) must equal( true )
      expected.## must equal( FooAggregateRoot.module.## )
      FooAggregateRoot.module.aggregateIdTag must equal( expected.aggregateIdTag )
      FooAggregateRoot.module.indexes must equal( expected.indexes )
      FooAggregateRoot.module must equal( expected )
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = Module.nextId.toOption.get
      val t = Module aggregateOf id
      t !+ EntityProtocol.Rename( id, "foobar" )
      bus.expectNoMsg( 5.seconds.dilated )
    }

    "add foo" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId.toOption.get
      val foo = Option(FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster"))
      val f = Module aggregateOf id
      f !+ EntityProtocol.Add( id, foo )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: EntityProtocol.Added =>  Module.toEntity( payload.info ).get.name mustBe "foo1"
      }
    }

    "update name" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId.toOption.get
      val f = Module aggregateOf id
      f !+ EntityProtocol.Add( id, Option(FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ EntityProtocol.Rename( id, "good-foo" )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo renamed" ) {
        case payload: EntityProtocol.Renamed => {
          payload.sourceId mustBe id
          payload.oldName mustBe "foo1"
          payload.newName mustBe "good-foo"
        }
      }
    }

    "update slug" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId.toOption.get
      val f = Module aggregateOf id
      f !+ EntityProtocol.Add( id, Option(FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      val newSlug = "gt"
      f !+ EntityProtocol.Reslug( id, newSlug )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo slug changed" ) {
        case payload: EntityProtocol.Reslugged => {
          payload.sourceId mustBe id
          payload.oldSlug mustBe "f1"
          payload.newSlug mustBe "gt"
        }
      }
    }

    "disable aggregate" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId.toOption.get
      val f = Module aggregateOf id
      f !+ EntityProtocol.Add( id, Option(FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ EntityProtocol.Disable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo disabled" ) {
        case payload: EntityProtocol.Disabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }
    }

    "enable from disable aggregate" in { fixture: Fixture =>
      import fixture._
      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val id = Module.nextId.toOption.get
      val f = Module aggregateOf id
      f !+ EntityProtocol.Add( id, Option(FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")) )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) {
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      f !+ EntityProtocol.Disable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo disabled" ) {
        case payload: EntityProtocol.Disabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }

      f !+ EntityProtocol.Enable( id )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo enabled" ) {
        case payload: EntityProtocol.Enabled => {
          payload.sourceId mustBe id
          payload.slug mustBe "f1"
        }
      }
    }

    "recorded in slug index" taggedAs WIP in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId.toOption.get
      val id = tid.id
      val f1 = Option(FooImpl(tid, "foo1", "f1", true, 17, 3.14159, "zedster"))

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ EntityProtocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      val countDown = new CountDownFunction[String]
      countDown await 250.millis.dilated

      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe Some(id)
    }

    "bar command to force concrete protocol implementation" in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId.toOption.get
      val id = tid.id
      val f1 = Option(FooImpl(tid, "foo1", "f1", true, 17, 3.14159, "zedster" ))

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ EntityProtocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe Some(id)

      import akka.pattern.ask
//      implicit val timeout = akka.util.Timeout( 1.second )
      val bevt = ( f ? Protocol.Bar( tid, 17 ) ).mapTo[Protocol.Barred]
      whenReady( bevt ) { e => e.b mustBe 17 }
    }

    "enablement actions translate in slug index" in { fixture: Fixture =>
      import fixture._

      val tid = Module.nextId.toOption.get
      val id = tid.id
      val f1 = Option(FooImpl(tid, "foo1", "f1", true, 17, 3.14159, "zedster"))

      system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )

      val f = Module aggregateOf tid
      f !+ EntityProtocol.Add( tid, f1 )
      bus.expectMsgPF( max = 5.seconds.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: EntityProtocol.Added => Module.toEntity( payload.info ).get.name mustBe "foo1"
      }

      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe Some(id)

      f !+ EntityProtocol.Disable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe None }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe None

      f !+ EntityProtocol.Enable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe Some(id)

      f !+ EntityProtocol.Enable( tid )
      new CountDownFunction[String] await 250.millis.dilated
      whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
      trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
      slugIndex.get( "f1" ) mustBe Some(id)
    }
  }
}
