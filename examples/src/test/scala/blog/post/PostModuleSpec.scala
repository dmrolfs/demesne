package blog.post

import akka.testkit.{TestProbe, TestActorRef, TestKit}
//import contoso.conference.registration.OrderModule
import demesne._
import demesne.testkit.{AggregateRootSpec, DemesneModuleFixture}
import org.scalatest.{Suite, Tag}
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliableMessage
import peds.commons.log.Trace
import sample.blog.post.PostModule.PostState
import sample.blog.post._


trait PostFixture extends DemesneModuleFixture { outer: Suite with TestKit =>
  def trace: Trace[_]

  override val module: AggregateRootModule = new PostModule { }

  override val context: Map[Symbol, Any] = {
    val result = super.context
    val makeAuthorListing = () => trace.block( "makeAuthorList" ){ testActor }
    //    val makeAuthorListing: () => ActorRef = () => { ClusterSharding(system).shardRegion(AuthorListingModule.shardName) }
    result + ( 'authorListing -> makeAuthorListing )
  }
}


/**
 * Created by damonrolfs on 9/18/14.
 */
class PostModuleSpec extends AggregateRootSpec[PostModuleSpec]( testkit.system ) with PostFixture {

  override val trace = Trace[PostModuleSpec]

  object WIP extends Tag( "wip" )

  "Post Module should" should {
    "add content" taggedAs( WIP ) in {
      val author = TestProbe()
      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
      val real = TestActorRef[PostModule.Post](
        PostModule.Post.props(
          meta = PostModule.aggregateRootType,
          authorListing = author.ref
        )
      ).underlyingActor
      real receive AddPost(id, content)
      real.state shouldBe PostState( id = id, content = content, published = false )
      author.expectMsgPF( hint = "PostAdded event (ignored in practice)" ) {
        case ReliableMessage( _, Envelope( payload: PostAdded, _ ) ) => payload.content shouldBe content
      }
    }

    "follow happy path" in {
      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing the post add command" )

      PostModule.aggregateOf( id ) ! AddPost( id, content )
      PostModule.aggregateOf( id ) ! ChangeBody( id, "new content" )
      PostModule.aggregateOf( id ) ! Publish( id )

      expectMsgPF() {
        case ReliableMessage( 1, Envelope( payload: PostAdded, _) ) => payload.content shouldBe content
      }

      expectMsgPF() {
        case ReliableMessage( 2, Envelope( payload: BodyChanged, _) ) => payload.body shouldBe "new content"
      }

      expectMsgPF() {
        case ReliableMessage( 3, Envelope( PostPublished( pid, _, title ), _) ) => {
          pid shouldBe id
          title shouldBe "Test Add"
        }
      }
    }
  }
}
