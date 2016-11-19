package sample.blog.author

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Success
import akka.testkit.{TestActorRef, TestProbe}
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import demesne._
import demesne.testkit._
import org.scalatest.{Outcome, Tag}
import peds.akka.envelope.{ComponentPath => EnvComponentPath, ComponentType => EnvComponentType, _}
import peds.akka.publish.ReliablePublisher.ReliableMessage
import peds.commons.identifier.{ShortUUID, TaggedID}
import peds.commons.log.Trace
import sample.blog.author.AuthorListingModule.{GetPosts, Posts}
import sample.blog.post.PostPrototol.PostPublished


object AuthorListingModuleSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/18/14.
 */
class AuthorListingModuleSpec extends ParallelAkkaSpec with StrictLogging {

  private val trace = Trace[AuthorListingModuleSpec]

  override def testSlug( test: OneArgTest ): String = "Blog-" + testPosition.incrementAndGet()

  override def createAkkaFixture( test: OneArgTest, config: Config, system: ActorSystem, slug: String ): Fixture = {
    new AuthorListingFixture( config, system, slug )
  }

  override type Fixture = AuthorListingFixture

  class AuthorListingFixture( _config: Config, _system: ActorSystem, _slug: String )
  extends AkkaFixture( _config, _system, _slug ) {
    private val trace = Trace[AuthorListingFixture]
    import scala.concurrent.ExecutionContext.Implicits.global

    override def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      import demesne.repository.StartProtocol

      import akka.pattern.AskableActorSelection
      val supervisorSel = new AskableActorSelection( system actorSelection s"/user/${boundedContext.name}-repositories" )
      implicit val timeout = Timeout( 5.seconds )
      Await.ready( ( supervisorSel ? StartProtocol.WaitForStart ), 5.seconds )
    }

    override def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType]

    val authorProbe = TestProbe()


    override lazy val boundedContext: BoundedContext = {
      val result = {
        for {
          made <- BoundedContext.make( Symbol(slug), config, userResources = AuthorListingModule.resources(system) )
          ready = made.withStartTask( AuthorListingModule.startTask )
          started <- ready.start()( global, Timeout(5.seconds) )
        } yield started
      }

      Await.result( result, 5.seconds )
    }
  }


  object WIP extends Tag( "wip" )

  val header = EnvelopeHeader(
    fromComponentType = EnvComponentType( "component-type" ),
    fromComponentPath = EnvComponentPath( "akka://Test/user/post" ),
    toComponentPath =  EnvComponentPath( "akka://Test/user/author" ),
    messageType = MessageType( "posting" ),
    workId = WorkId( ShortUUID() ),
    messageNumber = MessageNumber( 13 ),
    version = EnvelopeVersion( 7 )
  )

  def nextPostId: TaggedID[ShortUUID] = TaggedID( tag = 'post, id = ShortUUID() )

  "Author listing Module should" should {
    "extract cluster id from message" in { fixture: Fixture =>
      implicit val system = fixture.system

      val extractor = AuthorListingModule.AuthorListing.idExtractor
      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Extraction" )
      extractor( pp ) mustBe ( pp.author, pp )

      val gp = GetPosts( author = "Damon" )
      extractor( gp ) mustBe ( gp.author, gp )

      val epp = Envelope( payload = pp, header = header )
      extractor( epp ) mustBe ( pp.author, epp )
      val egp = Envelope( payload = gp, header = header )
      extractor( egp ) mustBe ( gp.author, egp )

      val rpp = ReliableMessage( 3L, epp )
      extractor( rpp ) mustBe (pp.author, rpp )
      val rgp = ReliableMessage( 7L, egp )
      extractor( rgp ) mustBe (gp.author, rgp )
    }

    "extract shard from message" in { fixture: Fixture =>
      implicit val system = fixture.system

      val shard = AuthorListingModule.AuthorListing.shardResolver
      val author = "Damon"
      val authorHash = ( math.abs( author.hashCode ) % 100 ).toString

      val pp = PostPublished( sourceId = nextPostId, author = author, title = "Extraction" )
      shard( pp ) mustBe authorHash

      val gp = GetPosts( author = "Damon" )
      shard( gp ) mustBe authorHash

      val epp = Envelope( payload = pp, header = header )
      shard( epp ) mustBe authorHash
      val egp = Envelope( payload = gp, header = header )
      shard( egp ) mustBe authorHash

      val rpp = ReliableMessage( 3L, epp )
      shard( rpp ) mustBe authorHash
      val rgp = ReliableMessage( 7L, egp )
      shard( rgp ) mustBe authorHash
    }

    "handle PostPublished event" in { fixture: Fixture =>
      import fixture._

      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
      val real = TestActorRef[AuthorListingModule.AuthorListing].underlyingActor
      real.posts mustBe Vector.empty
      real.receive( pp )
      real.posts mustBe IndexedSeq( pp )
    }

    "respond to GetPosts requests" in { fixture: Fixture =>
      import akka.pattern.ask
      import fixture._
      implicit val timeout = Timeout( 5.seconds )

      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
      val ref = TestActorRef[AuthorListingModule.AuthorListing]
      val real = ref.underlyingActor
      val expected: immutable.IndexedSeq[PostPublished] = immutable.IndexedSeq( pp )
      real.posts mustBe Vector.empty
      val r1 = ref ? GetPosts("Damon")
      val Success(Posts(a1)) = r1.value.get
      a1 mustBe immutable.IndexedSeq.empty

      real.receive( pp )
      val r2 = ref ? GetPosts( "Damon" )
      val Success(Posts(a2)) = r2.value.get
      a2 mustBe immutable.IndexedSeq( pp )
    }
  }
}
