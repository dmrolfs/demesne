package sample.blog.post

import akka.testkit._
import demesne._
import demesne.testkit.AggregateRootSpec
import demesne.testkit.concurrent.CountDownFunction
import org.scalatest.Tag
import peds.akka.envelope._
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.log.Trace

import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import com.typesafe.scalalogging.LazyLogging


/**
 * Created by damonrolfs on 9/18/14.
 */
class PostModuleSpec extends AggregateRootSpec[PostModuleSpec] with ScalaFutures with LazyLogging {

  private val trace = Trace[PostModuleSpec]

  override type Fixture = PostFixture

  class PostFixture extends AggregateFixture {
    private val trace = Trace[PostFixture]

    val bus: TestProbe = TestProbe()
    val author: TestProbe = TestProbe()

    // override val module: AggregateRootModule = new PostModule with AggregateModuleInitializationExtension { }
    def moduleCompanions: List[AggregateRootModule] = List( PostModule )

    override def context: Map[Symbol, Any] = trace.block( "context" ) {
      val result = super.context
      val makeAuthorListing = () => trace.block( "makeAuthorList" ){ author.ref }
      result + ( 'authorListing -> makeAuthorListing )
    }
  }

  override def createAkkaFixture(): Fixture = new PostFixture

  object WIP extends Tag( "wip" )
  object GOOD extends Tag( "good" )

  "Post Module should" should {
    // "config is okay" taggedAs(WIP) in { f: Fixture =>
    //   val config = f.system.settings.config
    //   config.getString( "akka.persistence.journal.plugin" ) mustBe "inmemory-journal"
    //   config.getString( "akka.persistence.snapshot-store.plugin" ) mustBe "inmemory-snapshot-store"
    // }

    "add content" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
      val post = PostModule aggregateOf id
      post !+ AddPost( id, content )
      bus.expectMsgPF( max = 800.millis.dilated, hint = "post added" ) { //DMR: Is this sensitive to total num of tests executed?
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post !+ ChangeBody( id, "dummy content" )
      post !+ Publish( id )
      bus.expectNoMsg( 200.millis.dilated )
    }

    "not respond to incomplete content" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post !+ AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
      bus.expectNoMsg( 200.millis.dilated )
      post !+ AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
      bus.expectNoMsg( 200.millis.dilated )
    }

    "have empty contents before use" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post.send( GetContent( id ) )( author.ref )
      author.expectMsgPF( max = 200.millis.dilated, hint = "empty contents" ){
        case Envelope( payload: PostContent, h ) => {
          payload mustBe PostContent( "", "", "" )
          h.messageNumber mustBe MessageNumber( 2 )
          h.workId must not be WorkId.unknown

        }
      }
    }

    "have contents after posting" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )

      val clientProbe = TestProbe()
      post !+ AddPost( id, content )
      post.send( GetContent( id ))( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "initial contents" ){
        case Envelope( payload: PostContent, h ) if payload == content => true
      }
    }

    "have changed contents after change" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val clientProbe = TestProbe()
      post !+ AddPost( id, content )
      bus.expectMsgPF( hint = "PostAdded" ) {
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }

      post !+ ChangeBody( id, updated )
      bus.expectMsgPF( hint = "BodyChanged" ) {
        case Envelope( payload: BodyChanged, _ ) => payload.body mustBe updated
      }

      post.send( GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis.dilated, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
    }

    "have changed contents after change and published" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post !+ AddPost( id, content )
      post !+ ChangeBody( id, updated )
      post !+ Publish( id )
      post.send( GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
    }

    "dont change contents after published" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post !+ AddPost( id, content )
      post !+ ChangeBody( id, updated )
      post !+ Publish( id )
      post !+ ChangeBody( id, "BAD CONTENT" )
      post.send( GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
    }

    "follow happy path" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      PostModule.aggregateOf( id ) !+ AddPost( id, content )
      PostModule.aggregateOf( id ) !+ ChangeBody( id, "new content" )
      PostModule.aggregateOf( id ) !+ Publish( id )

      bus.expectMsgPF( hint = "post-added" ) {
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }

      bus.expectMsgPF( hint = "body-changed" ) {
        case Envelope( payload: BodyChanged, _ ) => payload.body mustBe "new content"
      }

      bus.expectMsgPF( hint = "post-published local" ) {
        case Envelope( PostPublished( pid, _, title ), _ ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }

      author.expectMsgPF( hint = "post-published reliable" ) {
        case ReliableMessage( 1, Envelope( PostPublished( pid, _, title ), _) ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }
    }

    "recorded in author register after post added via bus" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.aggregateRootType
      val ar = model.aggregateRegisterFor[String]( rt, 'author )
      ar.isRight mustBe true
      for {
        register <- ar 
      } {
        val id = PostModule.nextId
        val content = PostContent( author="Damon", title="Test Add", body="testing author register add" )
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )

        val post = PostModule.aggregateOf( id )
        post !+ AddPost( id, content )
        bus.expectMsgPF( hint = "post-added" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        val countDown = new CountDownFunction[String]
        countDown await 200.millis.dilated

        whenReady( register.futureGet( "Damon" ) ) { result => result mustBe Some(id) }
        trace( s"""register:Damon = ${register.get("Damon")}""" )
        register.get( "Damon" ) mustBe Some(id)
      }
    }

    "recorded in title register after post added via event stream" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.aggregateRootType
      val ar = model.aggregateRegisterFor[String]( rt, 'title )
      ar.isRight mustBe true
      for {
        register <- ar 
      } {
        val p = TestProbe()

        val id = PostModule.nextId
        val content = PostContent( author="Damon", title="Test Add", body="testing author register add" )
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )
        system.eventStream.subscribe( p.ref, classOf[Envelope] )

        val post = PostModule.aggregateOf( id )
        post !+ AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        val countDown = new CountDownFunction[String]

        countDown await 200.millis.dilated
        whenReady( register.futureGet( "Test Add" ) ) { result => result mustBe Some(id) }

  //      countDown await 75.millis.dilated
        register.get( "Test Add" ) mustBe Some(id)
      }
    }

    "withdrawn title in register after post delete via event stream" taggedAs(WIP) in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.aggregateRootType
      val ar = model.aggregateRegisterFor[String]( rt, 'author )
      ar.isRight mustBe true
      val tr = model.aggregateRegisterFor[String]( rt, 'title )
      tr.isRight mustBe true
      for {
        authorRegister <- ar
        titleregister <- tr 
      } {
        val p = TestProbe()

        val id = PostModule.nextId
        val content = PostContent( author="Damon", title="Test Add", body="testing register add" )
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )
        system.eventStream.subscribe( p.ref, classOf[Envelope] )

        val post = PostModule.aggregateOf( id )
        post !+ AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( titleregister.futureGet( "Test Add" ) ) { result => result mustBe Some(id) }

  //      countDown await 75.millis.dilated
        titleregister.get( "Test Add" ) mustBe Some(id)

        post !+ Delete( id )

        bus.expectMsgPF( hint = "post-deleted" ) {
          case Envelope( payload: Deleted, _ ) => payload.sourceId mustBe id
        }

        p.expectMsgPF( hint = "post-deleted stream" ) {
          case Envelope( payload: Deleted, _ ) => payload.sourceId mustBe id
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( titleregister.futureGet( "Test Add" ) ) { result => 
          logger error s"HERE ****: result(Test Add) = $result"
          result mustBe None
        }

        whenReady( authorRegister.futureGet( "Damon" ) ) { result => 
          logger error s"HERE ****: result(Damon) = $result"
          result mustBe None
        }
      }
    }

    "revised title in register after post title change via event stream" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.aggregateRootType
      val ar = model.aggregateRegisterFor[String]( rt, 'author )
      ar.isRight mustBe true
      val tr = model.aggregateRegisterFor[String]( rt, 'title )
      tr.isRight mustBe true
      for {
        authorRegister <- ar
        titleRegister <- tr 
      } {
        val p = TestProbe()

        val id = PostModule.nextId
        val content = PostContent( author="Damon", title="Test Add", body="testing register add" )
        system.eventStream.subscribe( bus.ref, classOf[Envelope] )
        system.eventStream.subscribe( p.ref, classOf[Envelope] )

        val post = PostModule.aggregateOf( id )
        post !+ AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( authorRegister.futureGet( "Damon" ) ) { result => result mustBe Some(id) }
        whenReady( titleRegister.futureGet( "Test Add" ) ) { result => result mustBe Some(id) }

  //      countDown await 75.millis.dilated
        authorRegister.get( "Damon" ) mustBe Some(id)
        titleRegister.get( "Test Add" ) mustBe Some(id)

        post !+ ChangeTitle( id, "New Title" )

        bus.expectMsgPF( hint = "title-change" ) {
          case Envelope( payload: TitleChanged, _ ) => {
            payload.oldTitle mustBe "Test Add"
            payload.newTitle mustBe "New Title"
          }
        }

        p.expectMsgPF( hint = "post-title change stream" ) {
          case Envelope( payload: TitleChanged, _ ) => {
            payload.oldTitle mustBe "Test Add"
            payload.newTitle mustBe "New Title"
          }
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( titleRegister.futureGet( "New Title" ) ) { result => 
          logger error s"HERE ****: result(New Title) = $result"
          result mustBe Some(id) 
        }

        whenReady( titleRegister.futureGet( "Test Add" ) ) { result => 
          logger error s"HERE ****: result(Test Add) = $result"
          result mustBe None 
        }

        whenReady( authorRegister.futureGet( "Damon" ) ) { result => 
          logger error s"HERE ****: result(Damon) = $result"
          result mustBe Some(id)
        }
      }
    }
  }
}
