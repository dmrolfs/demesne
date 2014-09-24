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
import sample.blog.post.{PostContent, AddPost, PostAdded, PostModule}

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
      val command = AddPost(
        targetId = id,
        content = content
      )

      PostModule.aggregateOf( id ) ! command

      expectMsgPF() {
        case ReliableMessage( 1, Envelope( payload: PostAdded, _) ) => payload.content shouldBe content
      }
    }
  }
}
