package demesne.module

import scala.reflect._
import akka.actor.Props
import akka.testkit._

import scalaz.{-\/, \/-}
import scalaz.Scalaz._
import shapeless.Lens
import demesne._
import demesne.register.AggregateIndexSpec
import demesne.testkit.AggregateRootSpec
import org.scalatest.Tag
import peds.archetype.domain.model.core.{Entity, EntityIdentifying, EntityLensProvider}
import peds.commons.log.Trace
import peds.commons.identifier._
import org.scalatest.concurrent.ScalaFutures
import com.typesafe.scalalogging.LazyLogging
import peds.commons._


object SimpleAggregateModuleSpec {
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
    implicit val fooIdentifying: Identifying[Foo] = new EntityIdentifying[Foo] {
      override type ID = Foo#ID
      override val evEntity: ClassTag[Foo] = classTag[Foo]
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
    val trace = Trace[FooAggregateRoot.type]
    val module: SimpleAggregateModule[Foo] = trace.block( "foo-module" ) {
      val b = SimpleAggregateModule.builderFor[Foo].make
      import b.P.{ Tag => BTag, Props => BProps, _ }

      b.builder
       .set( BTag, 'fooTAG )
       .set( BProps, FooActor.props(_,_) )
       .build()
    }

    object FooActor {
      def props( model: DomainModel, meta: AggregateRootType ): Props = ???
    }
  }
}


abstract class SimpleAggregateModuleSpec extends AggregateRootSpec[SimpleAggregateModuleSpec] with ScalaFutures {

  private val trace = Trace[SimpleAggregateModuleSpec]

  override type ID = ShortUUID
  override type Protocol = SimpleAggregateModuleSpec.Protocol.type
  override val protocol: Protocol = SimpleAggregateModuleSpec.Protocol

  override type Fixture = TestFixture

  class TestFixture extends AggregateFixture {
    override val module: AggregateRootModule = SimpleAggregateModuleSpec.FooAggregateRoot.module
    def moduleCompanions: List[AggregateRootModule] = List() // List( SimpleAggregateModuleSpec.FooAggregateRoot.module )

    override def nextId(): TID = {
      import SimpleAggregateModuleSpec.Foo.{fooIdentifying => identifying}

      identifying.nextIdAs[TID] match {
        case \/-( r ) => r
        case -\/( ex ) => {
          logger.error( "failed to generate nextId", ex )
          throw ex
        }
      }
    }
  }

  override def createAkkaFixture( test: OneArgTest ): Fixture = new TestFixture

  object ADD extends Tag( "add" )
  object UPDATE extends Tag( "update" )
  object GOOD extends Tag( "good" )

  "Module should" should {
    import SimpleAggregateModuleSpec._
    // import FooAggregateRoot.{ module => Module }

    "simple" in { fixture: Fixture =>
      import fixture._
      val expected = SimpleAggregateModule.SimpleAggregateModuleImpl[Foo](
        aggregateIdTag = 'fooTAG, 
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props(_,_),
        indexes = Seq.empty[AggregateIndexSpec[_,_]]
      )

      val b = SimpleAggregateModule.builderFor[Foo].make
      import b.P.{ Tag => BTag, Props => BProps, _ }
      val actual = b.builder
                    .set( BTag, 'fooTAG )
                    .set( BProps, FooAggregateRoot.FooActor.props(_, _) )
                    .build

      actual.aggregateIdTag must equal( 'fooTAG )
    }

    "build module" in { fixture: Fixture =>
      import fixture._

      val expected = SimpleAggregateModule.SimpleAggregateModuleImpl[Foo](
        aggregateIdTag = 'fooTAG,
        aggregateRootPropsOp = FooAggregateRoot.FooActor.props(_,_),
        indexes = Seq.empty[AggregateIndexSpec[_,_]]
      )

      FooAggregateRoot.module must equal( expected )
    }

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


  // //   "not respond before added" in { fixture: Fixture =>
  // //     import fixture._

  // //     system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
  // //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     post !! ChangeBody( id, "dummy content" )
  // //     post !! Publish( id )
  // //     bus.expectNoMsg( 200.millis.dilated )
  // //   }

  // //   "not respond to incomplete content" in { fixture: Fixture =>
  // //     import fixture._

  // //     system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
  // //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     post !! AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
  // //     bus.expectNoMsg( 200.millis.dilated )
  // //     post !! AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
  // //     bus.expectNoMsg( 200.millis.dilated )
  // //   }

  // //   "have empty contents before use" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     post.send( GetContent( id ) )( author.ref )
  // //     author.expectMsgPF( max = 200.millis.dilated, hint = "empty contents" ){
  // //       case Envelope( payload: PostContent, h ) => {
  // //         payload mustBe PostContent( "", "", "" )
  // //         h.messageNumber mustBe MessageNumber( 2 )
  // //         h.workId must not be WorkId.unknown

  // //       }
  // //     }
  // //   }

  // //   "have contents after posting" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )

  // //     val clientProbe = TestProbe()
  // //     post !! AddPost( id, content )
  // //     post.send( GetContent( id ))( clientProbe.ref )
  // //     clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "initial contents" ){
  // //       case Envelope( payload: PostContent, h ) if payload == content => true
  // //     }
  // //   }

  // //   "have changed contents after change" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
  // //     val updated = "updated contents"

  // //     system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
  // //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  // //     val clientProbe = TestProbe()
  // //     post !! AddPost( id, content )
  // //     bus.expectMsgPF( hint = "PostAdded" ) {
  // //       case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
  // //     }

  // //     post !! ChangeBody( id, updated )
  // //     bus.expectMsgPF( hint = "BodyChanged" ) {
  // //       case Envelope( payload: BodyChanged, _ ) => payload.body mustBe updated
  // //     }

  // //     post.send( GetContent( id ) )( clientProbe.ref )
  // //     clientProbe.expectMsgPF( max = 200.millis.dilated, hint = "changed contents" ){
  // //       case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
  // //     }
  // //   }

  // //   "have changed contents after change and published" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
  // //     val updated = "updated contents"

  // //     val clientProbe = TestProbe()
  // //     post !! AddPost( id, content )
  // //     post !! ChangeBody( id, updated )
  // //     post !! Publish( id )
  // //     post.send( GetContent( id ) )( clientProbe.ref )
  // //     clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
  // //       case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
  // //     }
  // //   }

  // //   "dont change contents after published" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val post = PostModule aggregateOf id
  // //     val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
  // //     val updated = "updated contents"

  // //     val clientProbe = TestProbe()
  // //     post !! AddPost( id, content )
  // //     post !! ChangeBody( id, updated )
  // //     post !! Publish( id )
  // //     post !! ChangeBody( id, "BAD CONTENT" )
  // //     post.send( GetContent( id ) )( clientProbe.ref )
  // //     clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
  // //       case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
  // //     }
  // //   }

  // //   "follow happy path" in { fixture: Fixture =>
  // //     import fixture._

  // //     val id = PostModule.nextId
  // //     val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

  // //     system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
  // //     system.eventStream.subscribe( bus.ref, classOf[Envelope] )

  // //     PostModule.aggregateOf( id ) !! AddPost( id, content )
  // //     PostModule.aggregateOf( id ) !! ChangeBody( id, "new content" )
  // //     PostModule.aggregateOf( id ) !! Publish( id )

  // //     bus.expectMsgPF( hint = "post-added" ) {
  // //       case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
  // //     }

  // //     bus.expectMsgPF( hint = "body-changed" ) {
  // //       case Envelope( payload: BodyChanged, _ ) => payload.body mustBe "new content"
  // //     }

  // //     bus.expectMsgPF( hint = "post-published local" ) {
  // //       case Envelope( PostPublished( pid, _, title ), _ ) => {
  // //         pid mustBe id
  // //         title mustBe "Test Add"
  // //       }
  // //     }

  // //     author.expectMsgPF( hint = "post-published reliable" ) {
  // //       case ReliableMessage( 1, Envelope( PostPublished( pid, _, title ), _) ) => {
  // //         pid mustBe id
  // //         title mustBe "Test Add"
  // //       }
  // //     }
  // //   }

  // //   "recorded in title register after post added via event stream" in { fixture: Fixture =>
  // //     import fixture._

  // //     val rt = PostModule.aggregateRootType
  // //     val ar = model.aggregateRegisterFor( rt, 'title )
  // //     ar.isRight mustBe true
  // //     for {
  // //       register <- ar 
  // //     } {
  // //       val p = TestProbe()

  // //       val id = PostModule.nextId
  // //       val content = PostContent( author="Damon", title="Test Add", body="testing author register add" )
  // //       system.eventStream.subscribe( bus.ref, classOf[Envelope] )
  // //       system.eventStream.subscribe( p.ref, classOf[Envelope] )

  // //       val post = PostModule.aggregateOf( id )
  // //       post !! AddPost( id, content )

  // //       bus.expectMsgPF( hint = "post-added" ) {
  // //         case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
  // //       }

  // //       p.expectMsgPF( hint = "post-added stream" ) {
  // //         case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
  // //       }

  // //       val countDown = new CountDownFunction[String]

  // //       countDown await 200.millis.dilated
  // //       whenReady( register.futureGet( "Test Add" ) ) { result => result mustBe Some(id) }

  // // //      countDown await 75.millis.dilated
  // //       register.get( "Test Add" ) mustBe Some(id)
  // //     }
  // //   }
  }
}
