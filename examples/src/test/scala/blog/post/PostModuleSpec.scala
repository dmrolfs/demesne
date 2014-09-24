package blog.post

import akka.actor.ActorRef
import akka.contrib.pattern.ClusterSharding
import akka.testkit.TestProbe
import demesne._
import demesne.testkit.AggregateRootSpec
import peds.akka.envelope.Envelope
import peds.akka.publish.ReliableMessage
import peds.commons.log.Trace
import sample.blog.author.AuthorListingModule
import sample.blog.post._

import scala.reflect.ClassTag


/**
 * Created by damonrolfs on 9/18/14.
 */
class PostModuleSpec extends AggregateRootSpec[PostModuleSpec]( testkit.system ) {

  val trace = Trace[PostModuleSpec]


  override def context: Map[Symbol, Any] = {
    val result = super.context
    val makeAuthorListing = () => trace.block( "makeAuthorList" ){ testActor }
//    val makeAuthorListing: () => ActorRef = () => { ClusterSharding(system).shardRegion(AuthorListingModule.shardName) }
    result + ( 'authorListing -> makeAuthorListing )
  }

  override def module: AggregateRootModule = new PostModule { }

  "Post Module should" should {
    "add a post" in {
      val id = PostModule.nextId
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing the post add command" )

      PostModule.aggregateOf( id ) ! AddPost( id, content )
      expectMsgPF() {
        case ReliableMessage( 1, Envelope( payload: PostAdded, _) ) => payload.content shouldBe content
      }

      PostModule.aggregateOf( id ) ! ChangeBody( id, "new content" )
      expectMsgPF() {
        case ReliableMessage( 2, Envelope( payload: BodyChanged, _) ) => payload.body shouldBe "new content"
      }

      PostModule.aggregateOf( id ) ! Publish( id )
      expectMsgPF() {
        case ReliableMessage( 3, Envelope( PostPublished( pid, _, title ), _) ) => {
          pid shouldBe id
          title shouldBe "Test Add"
        }
      }
    }
  }
}
