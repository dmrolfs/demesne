package demesne.register

import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import com.typesafe.scalalogging.LazyLogging
import akka.actor.Props
import akka.event.LoggingReceive
import akka.testkit._
import scalaz._, Scalaz._
import demesne._
// import demesne.module.
import demesne.scaladsl._
import demesne.register.local.RegisterLocalAgent
import demesne.testkit.AggregateRootSpec
import demesne.testkit.concurrent.CountDownFunction
import org.scalatest.Tag
import peds.akka.envelope._
import peds.commons.identifier._
import peds.commons.log.Trace
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }


class RegisterAcceptanceSpecB extends AggregateRootSpec[RegisterAcceptanceSpecB] with ScalaFutures with LazyLogging {
  import AggregateRootModule.{ Command, Event }

  type TestAggregate = Map[Symbol, Any]
  type ID = ShortUUID
  type TID = TaggedID[ID]

  object TestAggregate {
    case class Add( override val targetId: Add#TID, foo: String, bar: Int ) extends Command[ID]
    case class ChangeBar( override val targetId: ChangeBar#TID, bar: Int ) extends Command[ID]
    case class Delete( override val targetId: Delete#TID ) extends Command[ID]

    case class Added( override val sourceId: Added#TID, foo: String, bar: Int ) extends Event[ID]
    case class BarChanged( override val sourceId: BarChanged#TID, oldBar: Int, newBar: Int ) extends Event[ID]
    case class Deleted( override val sourceId: Deleted#TID ) extends Event[ID]

    val builder = SimpleAggregateModuleBuilder[TestAggregate]
    val materialize = SimpleAggregateModuleBuilderInterpreter[TestAggregate]

    lazy val module: AggregateRootModule = materialize(
      for {
        _ <- builder.setIdTag( 'registerAcceptance )
        _ <- builder.setProps( TestAggregateActor.props( _, _ ) )
        _ <- builder.addIndex(
               RegisterLocalAgent.spec[String, TID]( 'bus, RegisterBusSubscription ) {
                 case Added( sid, foo, _ ) => Directive.Record( foo, sid )
                 case Deleted( sid ) => Directive.Withdraw( sid )
               }
             )
        _ <- builder.addIndex(
               RegisterLocalAgent.spec[Int, TID]( 'stream, ContextChannelSubscription( classOf[Added] ) ) {
                 case Added( sid, _, bar ) => Directive.Record( bar, sid )
                 case BarChanged( sid, oldBar, newBar ) => Directive.Revise( oldBar, newBar )
                 case Deleted( sid ) => Directive.Withdraw( sid )
               }
             )
        m <- builder.build
      } yield m
    )

    object TestAggregateActor {
      def props( model: DomainModel, meta: AggregateRootType ): Props = {
        Props( new TestAggregateActor( model, meta ) with StackableStreamPublisher with StackableRegisterBusPublisher )
      }
    }

    class TestAggregateActor( override val model: DomainModel, override val meta: AggregateRootType ) 
    extends AggregateRoot[TestAggregate] { publisher: EventPublisher =>
      override var state: TestAggregate = Map.empty[Symbol, Any]

      override def receiveCommand: Receive = around( active )
      
      val active: Receive = LoggingReceive {
        case Add( id, foo, bar ) => persistAsync( Added(id, foo, bar) ) { e => trace.block( "persist-Add" ) { acceptAndPublish( e ) } }

        case ChangeBar( id, newBar ) => persistAsync( BarChanged(id, state('bar).asInstanceOf[Int], newBar) ) { e => 
          trace.block( "persist-ChangeBar" ) { acceptAndPublish( e ) }
        }

        case Delete( id ) => persistAsync( Deleted(id) ) { e => trace.block( "persist-Delete" ) { acceptAndPublish( e ) } }
      }

      override val acceptance: Acceptance = {
        case ( BarChanged(id, _, newBar), state ) => state + ( 'bar -> newBar )
        case (_: Deleted, _) => Map.empty[Symbol, Any]
        case ( Added(id, foo, bar), state ) => trace.block( "accept-Added" ) { 
          trace( s"state=$state" )
          trace( s"id=$id" )
          trace( s"foo=$foo" )
          trace( s"bar=$bar" )

          Map[Symbol, Any]( 'id -> id, 'foo -> foo, 'bar -> bar ) 
        }
      }

    }
  }


  private val trace = Trace[RegisterAcceptanceSpec]

  override type Fixture = TestFixture

  class TestFixture extends AggregateFixture {
    private val trace = Trace[TestFixture]

    val bus: TestProbe = TestProbe()

    def moduleCompanions: List[AggregateRootModule] = List( TestAggregate.module )

    // override def context: Map[Symbol, Any] = trace.block( "context" ) {
    //   val result = super.context
    //   val makeAuthorListing = () => trace.block( "makeAuthorList" ){ author.ref }
    //   result + ( 'authorListing -> makeAuthorListing )
    // }
  }

  override def createAkkaFixture(): Fixture = new TestFixture

  object WIP extends Tag( "wip" )

  "Index RegisterB should" should {
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


    "recorded in registers after added" in { fixture: Fixture =>
      import fixture._

      val rt = TestAggregate.module.aggregateRootType
      val br = model.aggregateRegisterFor[String]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br 
        streamRegister <- sr
      } {
        val id = TestAggregate.module.nextId
        val foo = "Test Foo"
        val bar = 17
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )

        val aggregate = TestAggregate.module aggregateOf id
        aggregate !+ TestAggregate.Add( id, foo, bar )
        bus.expectMsgPF( hint = "added" ) {
          case Envelope( payload: TestAggregate.Added, _ ) => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDown = new CountDownFunction[String]
        countDown await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result => result mustBe Some(id) }
        trace( s"""bus-register:Test Foo = ${busRegister.get("Test Foo")}""" )
        busRegister.get( "Test Foo" ) mustBe Some(id)

        whenReady( streamRegister.futureGet( 17 ) ) { result => result mustBe Some(id) }
        trace( s"stream-register:17 = ${streamRegister.get(17)}" )
        streamRegister.get( 17 ) mustBe Some(id)
      }
    }

    "withdrawn from register after delete" in { fixture: Fixture =>
      import fixture._

      val rt = TestAggregate.module.aggregateRootType
      val br = model.aggregateRegisterFor[String]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr
      } {
        val p = TestProbe()

        val id = TestAggregate.module.nextId
        val foo = "Test Foo"
        val bar = 13
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )
        system.eventStream.subscribe( p.ref, classOf[Envelope] )

        val aggregate = TestAggregate.module aggregateOf id
        aggregate !+ TestAggregate.Add( id, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case Envelope( payload: TestAggregate.Added, _ ) => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case Envelope( payload: TestAggregate.Added, _ ) => {
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

        aggregate !+ TestAggregate.Delete( id )

        bus.expectMsgPF( hint = "bus-deleted" ) {
          case Envelope( payload: TestAggregate.Deleted, _ ) => payload.sourceId mustBe id
        }

        p.expectMsgPF( hint = "stream-deleted" ) {
          case Envelope( payload: TestAggregate.Deleted, _ ) => payload.sourceId mustBe id
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

    "revised in register after change" taggedAs(WIP) in { fixture: Fixture =>
      import fixture._

      val rt = TestAggregate.module.aggregateRootType
      val br = model.aggregateRegisterFor[String]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateRegisterFor[Int]( rt, 'stream )
      sr.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr 
      } {
        val p = TestProbe()

        val id = TestAggregate.module.nextId
        val foo = "Test Foo"
        val bar = 7
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )
        system.eventStream.subscribe( p.ref, classOf[Envelope] )

        val aggregate = TestAggregate.module aggregateOf id
        aggregate !+ TestAggregate.Add( id, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case Envelope( payload: TestAggregate.Added, _ ) => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case Envelope( payload: TestAggregate.Added, _ ) => {
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

        aggregate !+ TestAggregate.ChangeBar( id, 13 )

        bus.expectMsgPF( hint = "bar-change" ) {
          case Envelope( payload: TestAggregate.BarChanged, _ ) => {
            payload.oldBar mustBe 7
            payload.newBar mustBe 13
          }
        }

        p.expectMsgPF( hint = "post-bar change stream" ) {
          case Envelope( payload: TestAggregate.BarChanged, _ ) => {
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
