package demesne.register

import scala.reflect._
import akka.testkit._
import scalaz._, Scalaz._
import demesne._
import demesne.register.local.RegisterLocalAgent
import demesne.testkit.{AggregateRootSpec, SimpleTestModule}
import demesne.testkit.concurrent.CountDownFunction
import org.scalatest.Tag
import peds.akka.envelope._
import peds.commons.identifier.{ShortUUID, TaggedID}
import peds.commons.log.Trace

import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import com.typesafe.scalalogging.LazyLogging
import peds.archetype.domain.model.core.{Entity, EntityIdentifying, Identifying}
import peds.commons.TryV



class RegisterAcceptanceSpec extends AggregateRootSpec[RegisterAcceptanceSpec] with ScalaFutures with LazyLogging {
  case class Foo( override val id: TaggedID[ShortUUID], override val name: String, foo: String, bar: Int ) extends Entity {
    override type ID = ShortUUID
    override val evID: ClassTag[ID] = classTag[ShortUUID]
    override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]
  }

  object Foo {
    implicit lazy val fooIdentifying: Identifying[Foo] = new Identifying[Foo] {
      override type ID = Foo#ID
      override def idOf( o: Foo ): TID = o.id
      override lazy val evID: ClassTag[ID] = {
        val t: ClassTag[ID] = ClassTag( classOf[ShortUUID] )
        logger.error( "fooIdentifying classTag[ShortUUID] = [{}]", t)
        t
      }

      override lazy val evTID: ClassTag[TID] = {
        val t: ClassTag[TID] = ClassTag( classOf[TID] )
        logger.error( "fooIdentifying classTag[TaggedID[ShortUUID]] = [{}]", t )
        t
      }

      override def nextId: TryV[TID] = tag( ShortUUID() ).right
      override def fromString( idstr: String ): ID = ShortUUID( idstr )
    }
  }

  object Protocol extends AggregateProtocol[Foo#ID] {
    case class Add( override val targetId: Add#TID, foo: String, bar: Int ) extends Command
    case class ChangeBar( override val targetId: ChangeBar#TID, bar: Int ) extends Command
    case class Delete( override val targetId: Delete#TID ) extends Command

    case class Added( override val sourceId: Added#TID, foo: String, bar: Int ) extends Event
    case class BarChanged( override val sourceId: BarChanged#TID, oldBar: Int, newBar: Int ) extends Event
    case class Deleted( override val sourceId: Deleted#TID ) extends Event
  }

  object TestModule extends SimpleTestModule[Foo] { module =>
    override type ID = Foo#ID
    override lazy val evID: ClassTag[ID] = {
//      logger.error( "ClassTag( classOf[ShortUUID] ) = [{}]", ClassTag(classOf[ShortUUID]) )
//      logger.error( "Foo.evID = [{}]", ClassTag(classOf[ShortUUID]) )

      implicitly[Identifying[Foo]].bridgeIdClassTag[ShortUUID] match {
        case \/-( ctag ) => {
          logger.error( "TestModule.evId = ctag = [{}]", ctag )
          ctag
        }
        case -\/( ex ) => {
          logger.error( "fooIdentifying couldn't provide sufficient ClassTag:", ex )
          throw ex
        }
      }
    }  //classTag[ShortUUID] // todo how can I connect types Indeitfying[Foo] and TestModule.ID??


    override def nextId: TryV[TID] = implicitly[Identifying[Foo]].nextIdAs[TID]


    override def parseId( idstr: String ): ID = {
      val identifying = implicitly[Identifying[Foo]]
      identifying.idAs[ID]( identifying fromString idstr ) match {
        case scalaz.\/-( id ) => id
        case scalaz.-\/( ex ) => {
          logger.error( s"failed to parse id from string: [${idstr}]", ex )
          throw ex
        }
      }
    }

    override def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any] = {
      case Protocol.Add( id, foo, bar ) => Protocol.Added( id, foo, bar )
      case Protocol.ChangeBar( id, newBar ) => {
        logger error s"state = $state"
        Protocol.BarChanged( id, state('bar).asInstanceOf[Int], newBar )
      }
      case Protocol.Delete( id ) => Protocol.Deleted( id )
    }

    override def name: String = "RegisterAcceptance"

    override def indexes: Seq[AggregateIndexSpec[_, _]] = {
      Seq(
        RegisterLocalAgent.spec[String, TestModule.TID]( 'bus, RegisterBusSubscription ) {
          case Protocol.Added( sid, foo, _ ) => Directive.Record( foo, sid )
          case Protocol.Deleted( sid ) => Directive.Withdraw( sid )
        },
        RegisterLocalAgent.spec[Int, TestModule.TID]( 'stream, ContextChannelSubscription( classOf[Protocol.Event] ) ) {
          case Protocol.Added( sid, _, bar ) => Directive.Record( bar, sid )
          case Protocol.BarChanged( sid, oldBar, newBar ) => Directive.Revise( oldBar, newBar )
          case Protocol.Deleted( sid ) => Directive.Withdraw( sid )
        }
      )
    }

    override val acceptance: AggregateRoot.Acceptance[SimpleTestActor.State] = {
      case ( Protocol.Added(id, foo, bar), state ) => state + ( 'id -> id ) + ( 'foo -> foo ) + ( 'bar -> bar )
      case ( Protocol.BarChanged(id, _, newBar), state ) => state + ( 'bar -> newBar )
      case (_: Protocol.Deleted, _) => Map.empty[Symbol, Any]
    }
  }

  private val trace = Trace[RegisterAcceptanceSpec]

  override type Fixture = TestFixture

  class TestFixture extends AggregateFixture {
    private val trace = Trace[TestFixture]

    val bus: TestProbe = TestProbe()

    def moduleCompanions: List[AggregateRootModule] = List( TestModule )

    // override def context: Map[Symbol, Any] = trace.block( "context" ) {
    //   val result = super.context
    //   val makeAuthorListing = () => trace.block( "makeAuthorList" ){ author.ref }
    //   result + ( 'authorListing -> makeAuthorListing )
    // }
  }

  override def createAkkaFixture(): Fixture = new TestFixture

  object WIP extends Tag( "wip" )

  "Index Register should" should {
    // "config is okay" taggedAs(WIP) in { f: Fixture =>
    //   val config = f.system.settings.config
    //   config.getString( "akka.persistence.journal.plugin" ) mustBe "inmemory-journal"
    //   config.getString( "akka.persistence.snapshot-store.plugin" ) mustBe "inmemory-snapshot-store"
    // }

    // "add content" in { fixture: Fixture =>
    //   import fixture._

    //   system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
    //   system.eventStream.subscribe( bus.ref, classOf[Envelope] )

    //   val id = PostModule.nextId
    //   val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
    //   val post = PostModule aggregateOf id
    //   post !+ AddPost( id, content )
    //   bus.expectMsgPF( max = 800.millis.dilated, hint = "post added" ) { //DMR: Is this sensitive to total num of tests executed?
    //     case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
    //   }
    // }

    // "not respond before added" in { fixture: Fixture =>
    //   import fixture._

    //   system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
    //   system.eventStream.subscribe( bus.ref, classOf[Envelope] )

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   post !+ ChangeBody( id, "dummy content" )
    //   post !+ Publish( id )
    //   bus.expectNoMsg( 200.millis.dilated )
    // }

    // "not respond to incomplete content" in { fixture: Fixture =>
    //   import fixture._

    //   system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
    //   system.eventStream.subscribe( bus.ref, classOf[Envelope] )

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   post !+ AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
    //   bus.expectNoMsg( 200.millis.dilated )
    //   post !+ AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
    //   bus.expectNoMsg( 200.millis.dilated )
    // }

    // "have empty contents before use" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   post.send( GetContent( id ) )( author.ref )
    //   author.expectMsgPF( max = 200.millis.dilated, hint = "empty contents" ){
    //     case Envelope( payload: PostContent, h ) => {
    //       payload mustBe PostContent( "", "", "" )
    //       h.messageNumber mustBe MessageNumber( 2 )
    //       h.workId must not be WorkId.unknown

    //     }
    //   }
    // }

    // "have contents after posting" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )

    //   val clientProbe = TestProbe()
    //   post !+ AddPost( id, content )
    //   post.send( GetContent( id ))( clientProbe.ref )
    //   clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "initial contents" ){
    //     case Envelope( payload: PostContent, h ) if payload == content => true
    //   }
    // }

    // "have changed contents after change" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
    //   val updated = "updated contents"

    //   system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
    //   system.eventStream.subscribe( bus.ref, classOf[Envelope] )

    //   val clientProbe = TestProbe()
    //   post !+ AddPost( id, content )
    //   bus.expectMsgPF( hint = "PostAdded" ) {
    //     case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
    //   }

    //   post !+ ChangeBody( id, updated )
    //   bus.expectMsgPF( hint = "BodyChanged" ) {
    //     case Envelope( payload: BodyChanged, _ ) => payload.body mustBe updated
    //   }

    //   post.send( GetContent( id ) )( clientProbe.ref )
    //   clientProbe.expectMsgPF( max = 200.millis.dilated, hint = "changed contents" ){
    //     case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
    //   }
    // }

    // "have changed contents after change and published" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
    //   val updated = "updated contents"

    //   val clientProbe = TestProbe()
    //   post !+ AddPost( id, content )
    //   post !+ ChangeBody( id, updated )
    //   post !+ Publish( id )
    //   post.send( GetContent( id ) )( clientProbe.ref )
    //   clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
    //     case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
    //   }
    // }

    // "dont change contents after published" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val post = PostModule aggregateOf id
    //   val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
    //   val updated = "updated contents"

    //   val clientProbe = TestProbe()
    //   post !+ AddPost( id, content )
    //   post !+ ChangeBody( id, updated )
    //   post !+ Publish( id )
    //   post !+ ChangeBody( id, "BAD CONTENT" )
    //   post.send( GetContent( id ) )( clientProbe.ref )
    //   clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
    //     case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
    //   }
    // }

    // "follow happy path" in { fixture: Fixture =>
    //   import fixture._

    //   val id = PostModule.nextId
    //   val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

    //   system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
    //   system.eventStream.subscribe( bus.ref, classOf[Envelope] )

    //   PostModule.aggregateOf( id ) !+ AddPost( id, content )
    //   PostModule.aggregateOf( id ) !+ ChangeBody( id, "new content" )
    //   PostModule.aggregateOf( id ) !+ Publish( id )

    //   bus.expectMsgPF( hint = "post-added" ) {
    //     case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
    //   }

    //   bus.expectMsgPF( hint = "body-changed" ) {
    //     case Envelope( payload: BodyChanged, _ ) => payload.body mustBe "new content"
    //   }

    //   bus.expectMsgPF( hint = "post-published local" ) {
    //     case Envelope( PostPublished( pid, _, title ), _ ) => {
    //       pid mustBe id
    //       title mustBe "Test Add"
    //     }
    //   }

    //   author.expectMsgPF( hint = "post-published reliable" ) {
    //     case ReliableMessage( 1, Envelope( PostPublished( pid, _, title ), _) ) => {
    //       pid mustBe id
    //       title mustBe "Test Add"
    //     }
    //   }
    // }

    // override def indexes: Seq[AggregateIndexSpec[_, _]] = {
    //   Seq(
    //     RegisterLocalAgent.spec[String, TestModule.TID]( 'bus, RegisterBusSubscription ) {
    //       case Added( sid, foo, _ ) => Directive.Record( foo, sid )
    //       case Deleted( sid ) => Directive.Withdraw( sid )
    //     },
    //     RegisterLocalAgent.spec[Int, TestModule.TID]( 'stream, ContextChannelSubscription( classOf[Added] ) ) {
    //       case Added( sid, _, bar ) => Directive.Record( bar, sid )
    //       case BarChanged( sid, oldBar, newBar ) => Directive.Revise( oldBar, newBar )
    //       case Deleted( sid ) => Directive.Withdraw( sid )
    //     }
    //   )
    // }


    "recorded in registers after added" taggedAs WIP in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateRegisterFor[String, TestModule.TID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int, TestModule.TID]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br 
        streamRegister <- sr
      } {
        val tid = TestModule.nextId.toOption.get
        val id = tid.id
        logger.error( "DMR: test tid =[{}]", tid )
        val foo = "Test Foo"
        val bar = 17
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Added] )

        val aggregate = TestModule aggregateOf tid
        aggregate !+ Protocol.Add( tid, foo, bar )
        bus.expectMsgPF( hint = "added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe tid
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDown = new CountDownFunction[String]
        countDown await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => result mustBe Some(tid) }
        trace( s"""bus-register:Test Foo = ${busRegister.get("Test Foo")}""" )
        busRegister.get( "Test Foo" ) mustBe Some(tid)

        whenReady( streamRegister.futureGet( 17 ) ) { result => result mustBe Some(tid) }
        trace( s"stream-register:17 = ${streamRegister.get(17)}" )
        streamRegister.get( 17 ) mustBe Some(tid)
      }
    }

    "withdrawn from register after delete" in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateRegisterFor[String, TestModule.ID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int, TestModule.ID]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr
      } {
        val p = TestProbe()

        val id = TestModule.nextId.toOption.get
        val foo = "Test Foo"
        val bar = 13
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )
        system.eventStream.subscribe( p.ref, classOf[Protocol.Event] )

        val aggregate = TestModule aggregateOf id
        aggregate !+ Protocol.Add( id, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => result mustBe Some(id) }

  //      countDown await 75.millis.dilated
        busRegister.get( "Test Foo" ) mustBe Some(id)

        aggregate !+ Protocol.Delete( id )

        bus.expectMsgPF( hint = "bus-deleted" ) {
          case payload: Protocol.Deleted => payload.sourceId mustBe id
        }

        p.expectMsgPF( hint = "stream-deleted" ) {
          case payload: Protocol.Deleted => payload.sourceId mustBe id
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => 
          logger error s"HERE ****: result(Test Foo) = $result"
          result mustBe None
        }

        whenReady( streamRegister.futureGet( 13 ) ) { result => 
          logger error s"HERE ****: result(Damon) = $result"
          result mustBe None
        }
      }
    }

    "revised in register after change" in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateRegisterFor[String, TestModule.TID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int, TestModule.TID]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr 
      } {
        val p = TestProbe()

        val id = TestModule.nextId.toOption.get
        val foo = "Test Foo"
        val bar = 7
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )
        system.eventStream.subscribe( p.ref, classOf[Protocol.Event] )

        val aggregate = TestModule aggregateOf id
        aggregate !+ Protocol.Add( id, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => result mustBe Some(id) }
        whenReady( streamRegister.futureGet( 7 ) ) { result => result mustBe Some(id) }

  //      countDown await 75.millis.dilated
        busRegister.get( "Test Foo" ) mustBe Some(id)
        streamRegister.get( 7 ) mustBe Some(id)

        aggregate !+ Protocol.ChangeBar( id, 13 )

        bus.expectMsgPF( hint = "bar-change" ) {
          case payload: Protocol.BarChanged => {
            payload.oldBar mustBe 7
            payload.newBar mustBe 13
          }
        }

        p.expectMsgPF( hint = "post-bar change stream" ) {
          case payload: Protocol.BarChanged => {
            payload.oldBar mustBe 7
            payload.newBar mustBe 13
          }
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => 
          logger error s"HERE ****: result(Test Foo) = $result"
          result mustBe Some(id) 
        }

        whenReady( streamRegister.futureGet( 7 ) ) { result => 
          logger error s"HERE ****: result(7) = $result"
          result mustBe None 
        }

        whenReady( streamRegister.futureGet( 13 ) ) { result => 
          logger error s"HERE ****: result(13) = $result"
          result mustBe Some(id)
        }
      }
    }
  }
}
