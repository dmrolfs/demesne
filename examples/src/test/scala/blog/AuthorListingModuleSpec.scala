package sample.blog.author

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Success
import akka.testkit.{ TestActorRef, TestProbe }
import akka.util.Timeout
import demesne._
import demesne.testkit._
import org.scalatest.Tag
import omnibus.akka.envelope._
import omnibus.akka.publish.ReliablePublisher.ReliableMessage
import omnibus.identifier.ShortUUID
import sample.blog.author.AuthorListingModule.{ GetPosts, Posts }
import sample.blog.post.Post
import sample.blog.post.PostPrototol.PostPublished

object AuthorListingModuleSpec {
  val sysId = new AtomicInteger()
}

/**
  * Created by damonrolfs on 9/18/14.
  */
class AuthorListingModuleSpec extends ParallelAkkaSpec {

  override def createAkkaFixture(
    test: OneArgTest,
    system: ActorSystem,
    slug: String
  ): Fixture = {
    new AuthorListingFixture( system, slug )
  }

  override type Fixture = AuthorListingFixture

  class AuthorListingFixture( _system: ActorSystem, _slug: String )
      extends AkkaFixture( _slug, _system ) {

    import scala.concurrent.ExecutionContext.Implicits.global

    override def before( test: OneArgTest ): Unit = {
      import demesne.repository.StartProtocol

      import akka.pattern.AskableActorSelection
      val supervisorSel = new AskableActorSelection(
        system actorSelection s"/user/${boundedContext.name}-repositories"
      )
      implicit val timeout = Timeout( 5.seconds )
      Await.ready( (supervisorSel ? StartProtocol.WaitForStart), 5.seconds )
    }

    override def after( test: OneArgTest ): Unit = {}

    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType]

    val authorProbe = TestProbe()

    override lazy val boundedContext: BoundedContext = {
      val result = {
        for {
          made <- BoundedContext.make(
            Symbol( slug ),
            config,
            userResources = AuthorListingModule.resources( system )
          )
          ready = made.withStartTask( AuthorListingModule.startTask )
          started <- ready.start()( global, Timeout( 5.seconds ) )
        } yield started
      }

      Await.result( result, 5.seconds )
    }
  }

  object WIP extends Tag( "wip" )

  val header = EnvelopeHeader(
    fromComponentType = ComponentType( "component-type" ),
    fromComponentPath = ComponentPath( "akka://Test/user/post" ),
    toComponentPath = ComponentPath( "akka://Test/user/author" ),
    messageType = MessageType( "posting" ),
    workId = WorkId( ShortUUID() ),
    messageNumber = MessageNumber( 13 ),
    version = EnvelopeVersion( 7 ),
    properties = EnvelopeProperties()
  )

  def nextPostId: Post#TID = Post.identifying.next

  "Author listing Module should" should {
    "extract cluster id from message" in { fixture: Fixture =>
//      implicit val system = fixture.system

      val extractor = AuthorListingModule.AuthorListing.idExtractor
      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Extraction" )
      extractor( pp ) shouldBe ( ( pp.author, pp ) )

      val gp = GetPosts( author = "Damon" )
      extractor( gp ) shouldBe ( ( gp.author, gp ) )

      val epp = Envelope( payload = pp, header = header )
      extractor( epp ) shouldBe ( ( pp.author, epp ) )
      val egp = Envelope( payload = gp, header = header )
      extractor( egp ) shouldBe ( ( gp.author, egp ) )

      val rpp = ReliableMessage( 3L, epp )
      extractor( rpp ) shouldBe ( ( pp.author, rpp ) )
      val rgp = ReliableMessage( 7L, egp )
      extractor( rgp ) shouldBe ( ( gp.author, rgp ) )
    }

    "extract shard from message" in { fixture: Fixture =>
//      implicit val system = fixture.system

      val shard = AuthorListingModule.AuthorListing.shardResolver
      val author = "Damon"
      val authorHash = (math.abs( author.hashCode ) % 100).toString

      val pp = PostPublished( sourceId = nextPostId, author = author, title = "Extraction" )
      shard( pp ) shouldBe authorHash

      val gp = GetPosts( author = "Damon" )
      shard( gp ) shouldBe authorHash

      val epp = Envelope( payload = pp, header = header )
      shard( epp ) shouldBe authorHash
      val egp = Envelope( payload = gp, header = header )
      shard( egp ) shouldBe authorHash

      val rpp = ReliableMessage( 3L, epp )
      shard( rpp ) shouldBe authorHash
      val rgp = ReliableMessage( 7L, egp )
      shard( rgp ) shouldBe authorHash
    }

    "handle PostPublished event" in { fixture: Fixture =>
      import fixture._

      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
      val real = TestActorRef[AuthorListingModule.AuthorListing].underlyingActor
      real.posts shouldBe Vector.empty
      real.receive( pp )
      real.posts shouldBe IndexedSeq( pp )
    }

    "respond to GetPosts requests" in { fixture: Fixture =>
      import akka.pattern.ask
      import fixture._
      implicit val timeout = Timeout( 5.seconds )

      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
      val ref = TestActorRef[AuthorListingModule.AuthorListing]
      val real = ref.underlyingActor
//      val expected: immutable.IndexedSeq[PostPublished] = immutable.IndexedSeq( pp )
      real.posts shouldBe Vector.empty
      val r1 = ref ? GetPosts( "Damon" )
      val Success( Posts( a1 ) ) = r1.value.get
      a1 shouldBe immutable.IndexedSeq.empty

      real.receive( pp )
      val r2 = ref ? GetPosts( "Damon" )
      val Success( Posts( a2 ) ) = r2.value.get
      a2 shouldBe immutable.IndexedSeq( pp )
    }
  }
}
