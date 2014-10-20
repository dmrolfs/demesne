package sample.blog.post

import akka.testkit.TestProbe
import demesne._
import demesne.testkit.AggregateRootSpec
import org.scalatest.Tag
import peds.akka.envelope.{Envelope, MessageNumber, WorkId}
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.log.Trace

import scala.concurrent.duration._

/**
 * Created by damonrolfs on 9/18/14.
 */
class PostModuleSpec extends AggregateRootSpec[PostModuleSpec] {

  private val trace = Trace[PostModuleSpec]

  override type Fixture = PostFixture

  class PostFixture extends AggregateFixture {
    private val trace = Trace[PostFixture]

    val bus: TestProbe = TestProbe()
    val author: TestProbe = TestProbe()

    override val module: AggregateRootModule = new PostModule { }

    override def context: Map[Symbol, Any] = trace.block( "context" ) {
      val result = super.context
      val makeAuthorListing = () => trace.block( "makeAuthorList" ){ author.ref }
      result + ( 'authorListing -> makeAuthorListing )
    }
  }

  override def createAkkaFixture(): Fixture = new PostFixture

  object WIP extends Tag( "wip" )

  "Post Module should" should {
    "add content" in { fixture: Fixture =>
      import fixture._

      trace( ">>>>>>>>  add content >>>>>>>>" )
      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
      val post = PostModule aggregateOf id
      post ! AddPost( id, content )
      bus.expectMsgPF( max = 400.millis, hint = "post added" ) { //DMR: Is this sensitive to total num of tests executed?
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }
      trace( "<<<<<<<< add content <<<<<<<<" )
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> not respond before added >>>>>>>>" )

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post ! ChangeBody( id, "dummy content" )
      post ! Publish( id )
      bus.expectNoMsg( 200.millis )
      trace( "<<<<<<<< not respond before added <<<<<<<<" )
    }

    "not respond to incomplete content" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> not respond to incomplete content >>>>>>>>" )

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post ! AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
      bus.expectNoMsg( 200.millis )
      post ! AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
      bus.expectNoMsg( 200.millis )
      trace( "<<<<<<<< not respond to incomplete content <<<<<<<<" )
    }

    "have empty contents before use" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> have empty contents before use >>>>>>>>" )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post.tell( GetContent( id ), author.ref )
      author.expectMsgPF( max = 200.millis, hint = "empty contents" ){
        case Envelope( payload: PostContent, h ) => {
          payload mustBe PostContent( "", "", "" )
          h.messageNumber mustBe MessageNumber( 2 )
          h.workId must not be WorkId.unknown

        }
      }
      trace( "<<<<<<<< have empty contents before use <<<<<<<<" )
    }

    "have contents after posting" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> have contents after posting >>>>>>>>" )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )

      val clientProbe = TestProbe()
      post ! AddPost( id, content )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "initial contents" ){
        case Envelope( payload: PostContent, h ) if payload == content => true
      }
      trace( "<<<<<<<< have contents after posting <<<<<<<<" )
    }

    "have changed contents after change" taggedAs( WIP ) in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> have changed contents after change >>>>>>>>" )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      val clientProbe = TestProbe()
      post ! AddPost( id, content )
      bus.expectMsgPF( hint = "PostAdded" ) {
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }

      post ! ChangeBody( id, updated )
      bus.expectMsgPF( hint = "BodyChanged" ) {
        case Envelope( payload: BodyChanged, _ ) => payload.body mustBe updated
      }

      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
      trace( "<<<<<<<< have changed contents after change <<<<<<<<" )
    }

    "have changed contents after change and published" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> have changed contents after change and published >>>>>>>>" )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post ! AddPost( id, content )
      post ! ChangeBody( id, updated )
      post ! Publish( id )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
      trace( "<<<<<<<< have changed contents after change and published <<<<<<<<" )
    }

    "dont change contents after published" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> dont change contents after published >>>>>>>>" )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post ! AddPost( id, content )
      post ! ChangeBody( id, updated )
      post ! Publish( id )
      post ! ChangeBody( id, "BAD CONTENT" )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
      trace( "<<<<<<<< dont change contents after published <<<<<<<<" )
    }

    "follow happy path" in { fixture: Fixture =>
      import fixture._
      trace( ">>>>>>>> follow happy path >>>>>>>>" )

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[Envelope] )

      PostModule.aggregateOf( id ) ! AddPost( id, content )
      PostModule.aggregateOf( id ) ! ChangeBody( id, "new content" )
      PostModule.aggregateOf( id ) ! Publish( id )

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
      trace( "<<<<<<<< follow happy path <<<<<<<<" )
    }
  }
}
