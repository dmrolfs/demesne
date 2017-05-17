package demesne.module

import scala.reflect._
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.Config

import cats.syntax.either._
import shapeless.Lens
import demesne._
import demesne.testkit.AggregateRootSpec
import org.scalatest.Tag
import omnibus.archetype.domain.model.core.{Entity, EntityIdentifying, EntityLensProvider}
import omnibus.commons.log.Trace
import omnibus.commons.identifier._
import org.scalatest.concurrent.ScalaFutures
import demesne.index.IndexSpecification
import omnibus.commons._


object SimpleAggregateModuleSpec {
  object Protocol extends AggregateProtocol[Foo#ID] {
    case class Bar( targetId: Bar#TID, b: Int ) extends Command
    case class Barred( sourceId: Barred#TID, b: Int ) extends Event
  }

  trait Foo extends Entity {
    override type ID = ShortUUID

    def isActive: Boolean
    def f: Int
    def b: Double
    def z: String
  }

  object Foo extends EntityLensProvider[Foo] {
    implicit val fooIdentifying: EntityIdentifying[Foo] = new EntityIdentifying[Foo] {
      override lazy val idTag: Symbol = 'fooTAG
      override def nextTID: ErrorOr[TID] = tag( ShortUUID() ).asRight
      override def idFromString( idRep: String ): ShortUUID = ShortUUID fromString idRep
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
    val trace = Trace[FooAggregateRoot.type]
    val module: SimpleAggregateModule[Foo, Foo#ID] = trace.block( "foo-module" ) {
      val b = SimpleAggregateModule.builderFor[Foo, Foo#ID].make
      import b.P.{ Props => BProps, _ }

      b.builder
//       .set( BTag, 'fooTAG )
       .set( BProps, FooActor.props(_,_) )
       .build()
    }

    object FooActor {
      def props( model: DomainModel, meta: AggregateRootType ): Props = ???
    }
  }
}


abstract class SimpleAggregateModuleSpec extends AggregateRootSpec[SimpleAggregateModuleSpec] with ScalaFutures {
  import SimpleAggregateModuleSpec.Foo

  private val trace = Trace[SimpleAggregateModuleSpec]


  override type State = Foo
  override type ID = ShortUUID
  override type Protocol = SimpleAggregateModuleSpec.Protocol.type
  override val protocol: Protocol = SimpleAggregateModuleSpec.Protocol


  override def createAkkaFixture( test: OneArgTest, config: Config, system: ActorSystem, slug: String ): Fixture = {
    new TestFixture( config, system, slug )
  }

  override type Fixture = TestFixture

  class TestFixture( _config: Config, _system: ActorSystem, _slug: String ) extends AggregateFixture( _config, _system, _slug ) {
    override val module: AggregateRootModule[Foo, Foo#ID] = SimpleAggregateModuleSpec.FooAggregateRoot.module

    override def rootTypes: Set[AggregateRootType] = Set( module.rootType )

    override def nextId(): TID = SimpleAggregateModuleSpec.Foo.fooIdentifying.nextTID.unsafeGet
//    {
//      import SimpleAggregateModuleSpec.Foo.{fooIdentifying => identifying}
//
//      identifying.nextIdAs[TID] match {
//        case \/-( r ) => r
//        case -\/( ex ) => {
//          logger.error( "failed to generate nextId", ex )
//          throw ex
//        }
//      }
//    }
  }


  object ADD extends Tag( "add" )
  object UPDATE extends Tag( "update" )
  object GOOD extends Tag( "good" )

  "Module should" should {
    import SimpleAggregateModuleSpec._

    "simple" in { fixture: Fixture =>
      import fixture._
      val expected = SimpleAggregateModule.SimpleAggregateModuleImpl[Foo, Foo#ID](
//        aggregateIdTag = 'fooTAG,
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props(_,_),
        passivateTimeout = AggregateRootType.DefaultPassivation,
        snapshotPeriod = Some( AggregateRootType.DefaultSnapshotPeriod ),
        startTask = StartTask.empty( "simple" ),
        environment = AggregateEnvironment.Resolver.local,
        clusterRole = None,
        indexes = Seq.empty[IndexSpecification]
      )

      val b = SimpleAggregateModule.builderFor[Foo, Foo#ID].make
      import b.P.{ Props => BProps, _ }
      val actual = b.builder
//                    .set( BTag, 'fooTAG )
                    .set( BProps, FooAggregateRoot.FooActor.props(_, _) )
                    .build

//      actual.aggregateIdTag must equal( 'fooTAG )
    }

    "build module" in { fixture: Fixture =>
      import fixture._

      val expected = SimpleAggregateModule.SimpleAggregateModuleImpl[Foo, Foo#ID](
//        aggregateIdTag = 'fooTAG,
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props(_,_),
        passivateTimeout = AggregateRootType.DefaultPassivation,
        snapshotPeriod = Some( AggregateRootType.DefaultSnapshotPeriod ),
        startTask = StartTask.empty( "expected" ),
        environment = AggregateEnvironment.Resolver.local,
        clusterRole = None,
        indexes = Seq.empty[IndexSpecification]
      )

      FooAggregateRoot.module must equal( expected )
    }

//todo resolve test status -- incorporate into EntityAggregateModuleSpec?

  //   "not respond before added" in { fixture: Fixture =>
  //     import fixture._

  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val t = Module aggregateOf id
  //     t !+ Module.Rename( id, "foobar" )
  //     bus.expectNoMsg( 200.millis.dilated )
  //   }

  //   "add foo" taggedAs(ADD) in { fixture: Fixture =>
  //     import fixture._

  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val foo = FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")
  //     val f = Module aggregateOf id
  //     f !+ Module.Add( foo )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }
  //   }

  //   "update name" taggedAs(UPDATE) in { fixture: Fixture =>
  //     import fixture._
  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val f = Module aggregateOf id
  //     f !+ Module.Add( FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster") )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) {
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     f !+ Module.Rename( id, "good-foo" )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo renamed" ) {
  //       case Envelope( payload: Module.Renamed, _ ) => {
  //         payload.sourceId mustBe id
  //         payload.oldName mustBe "foo1"
  //         payload.newName mustBe "good-foo"
  //       }
  //     }
  //   }

  //   "update slug" in { fixture: Fixture =>
  //     import fixture._
  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val f = Module aggregateOf id
  //     f !+ Module.Add( FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster") )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) {
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     val newSlug = "gt"
  //     f !+ Module.Reslug( id, newSlug )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo slug changed" ) {
  //       case Envelope( payload: Module.Reslugged, _ ) => {
  //         payload.sourceId mustBe id
  //         payload.oldSlug mustBe "f1"
  //         payload.newSlug mustBe "gt"
  //       }
  //     }
  //   }

  //   "disable aggregate" in { fixture: Fixture =>
  //     import fixture._
  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val f = Module aggregateOf id
  //     f !+ Module.Add( FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster") )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) {
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     f !+ Module.Disable( id )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo disabled" ) {
  //       case Envelope( payload: Module.Disabled, _ ) => {
  //         payload.sourceId mustBe id
  //         payload.slug mustBe "f1"
  //       }
  //     }
  //   }

  //   "enable from disable aggregate" in { fixture: Fixture =>
  //     import fixture._
  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val id = Module.nextId
  //     val f = Module aggregateOf id
  //     f !+ Module.Add( FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster") )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) {
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     f !+ Module.Disable( id )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo disabled" ) {
  //       case Envelope( payload: Module.Disabled, _ ) => {
  //         payload.sourceId mustBe id
  //         payload.slug mustBe "f1"
  //       }
  //     }

  //     f !+ Module.Enable( id )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo enabled" ) {
  //       case Envelope( payload: Module.Enabled, _ ) => {
  //         payload.sourceId mustBe id
  //         payload.slug mustBe "f1"
  //       }
  //     }
  //   }

  //   "recorded in slug index" in { fixture: Fixture =>
  //     import fixture._

  //     val id = Module.nextId
  //     val f1 = FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")

  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val f = Module aggregateOf id
  //     f !+ Module.Add( f1 )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     val countDown = new CountDownFunction[String]
  //     countDown await 200.millis.dilated

  //     whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
  //     trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
  //     slugIndex.get( "f1" ) mustBe Some(id)
  //   }

  //   "enablement actions translate in slug index" taggedAs(WIP) in { fixture: Fixture =>
  //     import fixture._

  //     val id = Module.nextId
  //     val f1 = FooImpl(id, "foo1", "f1", true, 17, 3.14159, "zedster")

  //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  //     val f = Module aggregateOf id
  //     f !+ Module.Add( f1 )
  //     bus.expectMsgPF( max = 800.millis.dilated, hint = "foo added" ) { //DMR: Is this sensitive to total num of tests executed?
  //       case Envelope( payload: Module.Added, _ ) => payload.info.name mustBe "foo1"
  //     }

  //     new CountDownFunction[String] await 200.millis.dilated
  //     whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
  //     trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
  //     slugIndex.get( "f1" ) mustBe Some(id)

  //     f !+ Module.Disable( id )
  //     new CountDownFunction[String] await 200.millis.dilated
  //     whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe None }
  //     trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
  //     slugIndex.get( "f1" ) mustBe None

  //     f !+ Module.Enable( id )
  //     new CountDownFunction[String] await 200.millis.dilated
  //     whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
  //     trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
  //     slugIndex.get( "f1" ) mustBe Some(id)

  //     f !+ Module.Enable( id )
  //     new CountDownFunction[String] await 200.millis.dilated
  //     whenReady( slugIndex.futureGet( "f1" ) ) { result => result mustBe Some(id) }
  //     trace( s"""index:f1 = ${slugIndex.get("f1")}""" )
  //     slugIndex.get( "f1" ) mustBe Some(id)
  //   }


  }
}
