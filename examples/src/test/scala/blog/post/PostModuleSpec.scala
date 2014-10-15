package sample.blog.post

import akka.testkit.TestProbe
import demesne._
import demesne.testkit.AggregateRootSpec
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

    val probe: TestProbe = TestProbe()

    override def module: AggregateRootModule = new PostModule { }

    override def context: Map[Symbol, Any] = {
      val result = super.context
      val makeAuthorListing = () => trace.block( "makeAuthorList" ){ probe.ref }
      result + ( 'authorListing -> makeAuthorListing )
    }
  }

  override def createAkkaFixture(): Fixture = new PostFixture

  "Post Module should" should {
    "add content" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( probe.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( probe.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
      val post = PostModule aggregateOf id
      post ! AddPost( id, content )
      probe.expectMsgPF( max = 1.second, hint = "post added" ) { //DMR: Is this sensitive to total num of tests executed?
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( probe.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( probe.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post ! ChangeBody( id, "dummy content" )
      post ! Publish( id )
      probe.expectNoMsg( 200.millis )
    }

    "not respond to incomplete content" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( probe.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( probe.ref, classOf[Envelope] )

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post ! AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
      probe.expectNoMsg( 200.millis )
      post ! AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
      probe.expectNoMsg( 200.millis )
    }

    "have empty contents before use" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      post.tell( GetContent( id ), probe.ref )
      probe.expectMsgPF( max = 200.millis, hint = "empty contents" ){
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
      post ! AddPost( id, content )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "initial contents" ){
        case Envelope( payload: PostContent, h ) if payload == content => true
      }
    }

    "have changed contents after change" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post ! AddPost( id, content )
      post ! ChangeBody( id, updated )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
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
      post ! AddPost( id, content )
      post ! ChangeBody( id, updated )
      post ! Publish( id )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
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
      post ! AddPost( id, content )
      post ! ChangeBody( id, updated )
      post ! Publish( id )
      post ! ChangeBody( id, "BAD CONTENT" )
      post.tell( GetContent( id ), clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
      }
    }

    "follow happy path" in { fixture: Fixture =>
      import fixture._

      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

      system.eventStream.subscribe( probe.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( probe.ref, classOf[Envelope] )

      PostModule.aggregateOf( id ) ! AddPost( id, content )
      PostModule.aggregateOf( id ) ! ChangeBody( id, "new content" )
      PostModule.aggregateOf( id ) ! Publish( id )

      probe.expectMsgPF( hint = "post-added" ) {
        case Envelope( payload: PostAdded, _ ) => payload.content mustBe content
      }

      probe.expectMsgPF( hint = "body-changed" ) {
        case Envelope( payload: BodyChanged, _ ) => payload.body mustBe "new content"
      }

      probe.expectMsgPF( hint = "post-published local" ) {
        case Envelope( PostPublished( pid, _, title ), _ ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }

      probe.expectMsgPF( hint = "post-published reliable" ) {
        case ReliableMessage( 1, Envelope( PostPublished( pid, _, title ), _) ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }
    }
  }
}
