package sample.blog.author

import java.util.concurrent.atomic.AtomicInteger

import akka.testkit.TestProbe
import demesne._
import demesne.testkit._
import org.scalatest.{Outcome, Tag}
import peds.akka.envelope._
import peds.akka.publish.ReliableMessage
import peds.commons.identifier.{ShortUUID, TaggedID}
import peds.commons.log.Trace
import sample.blog.author.AuthorListingModule.GetPosts
import sample.blog.post._


object AuthorListingModuleSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/18/14.
 */
class AuthorListingModuleSpec extends ParallelAkkaSpec {

  private val trace = Trace[AuthorListingModuleSpec]

  override type Fixture = AuthorListingFixture

  class AuthorListingFixture extends AkkaFixture {
    private val trace = Trace[AuthorListingFixture]

    def before(): Unit = trace.block( "before" ) { module start context }
    def after(): Unit = trace.block( "after" ) { module stop context }

    def module: AggregateRootModule = new PostModule { }

    def model: DomainModel = trace.block( s"model()" ) { DomainModel() }

    val authorProbe = TestProbe()

    def context: Map[Symbol, Any] = trace.block( "context()" ) {
      val makeAuthorListing = () => trace.block( "makeAuthorList" ){ authorProbe.ref }

      Map(
        demesne.ModelKey -> model,
        demesne.SystemKey -> system,
        demesne.FactoryKey -> demesne.factory.systemFactory,
        'authorListing -> makeAuthorListing
      )
    }
  }

  override def withFixture( test: OneArgTest ): Outcome = trace.block( s"withFixture(${test}})" ) {
    val sys = createAkkaFixture()
    trace( s"sys.module = ${sys.module}" )
    trace( s"sys.model = ${sys.model}" )
    trace( s"sys.context = ${sys.context}" )

    try {
      sys.before()
      test( sys )
    } finally {
      sys.after()
      sys.system.shutdown()
    }
  }

  override def createAkkaFixture(): Fixture = new AuthorListingFixture

  object WIP extends Tag( "wip" )

  val header = EnvelopeHeader(
    fromComponentType = ComponentType( "component-type" ),
    fromComponentPath = ComponentPath( "akka://Test/user/post" ),
    toComponentPath =  ComponentPath( "akka://Test/user/author" ),
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

//    "handle PostPublished event" in { fixture: Fixture =>
//      val real = TestActorRef[AuthorListingModule.AuthorListing].underlyingActor
//      val
//      real.receive( )
//    }

    //    "add content" taggedAs( WIP, ADD ) in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val content = PostContent( author = "Damon", title = "Add Content", body = "add body content" )
//      val post = PostModule aggregateOf id
//      post ! AddPost( id, content )
//      probe.expectMsgPF( max = 800.millis, hint = "post added" ) {
//        case ReliableMessage( _, Envelope( payload: PostAdded, _ ) ) => payload.content mustBe content
//      }
//    }
//
//    "not respond before added" taggedAs( NOACTION ) in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      post ! ChangeBody( id, "dummy content" )
//      post ! Publish( id )
//      probe.expectNoMsg( 200.millis )
//    }
//
//    "not respond to incomplete content" taggedAs( NOACTION ) in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      post ! AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
//      probe.expectNoMsg( 200.millis )
//      post ! AddPost( id, PostContent( author = "", title = "Incomplete Content", body = "no author" ) )
//      probe.expectNoMsg( 200.millis )
//    }
//
//    "have empty contents before use" in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      post.tell( GetContent( id ), probe.ref )
//      probe.expectMsgPF( max = 200.millis, hint = "empty contents" ){
//        case Envelope( payload: PostContent, h ) => {
//          payload mustBe PostContent( "", "", "" )
//          h.messageNumber mustBe MessageNumber( 2 )
//          h.workId must not be WorkId.unknown
//
//        }
//      }
//    }
//
//    "have contents after posting" in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
//
//      val clientProbe = TestProbe()
//      post ! AddPost( id, content )
//      post.tell( GetContent( id ), clientProbe.ref )
//      clientProbe.expectMsgPF( max = 200.millis, hint = "initial contents" ){
//        case Envelope( payload: PostContent, h ) if payload == content => true
//      }
//    }
//
//    "have changed contents after change" in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
//      val updated = "updated contents"
//
//      val clientProbe = TestProbe()
//      post ! AddPost( id, content )
//      post ! ChangeBody( id, updated )
//      post.tell( GetContent( id ), clientProbe.ref )
//      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
//        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
//      }
//    }
//
//    //todo: test incomplete PostContent
//
//    "have changed contents after change and published" in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
//      val updated = "updated contents"
//
//      val clientProbe = TestProbe()
//      post ! AddPost( id, content )
//      post ! ChangeBody( id, updated )
//      post ! Publish( id )
//      post.tell( GetContent( id ), clientProbe.ref )
//      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
//        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
//      }
//    }
//
//    "dont change contents after published" in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val post = PostModule aggregateOf id
//      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
//      val updated = "updated contents"
//
//      val clientProbe = TestProbe()
//      post ! AddPost( id, content )
//      post ! ChangeBody( id, updated )
//      post ! Publish( id )
//      post ! ChangeBody( id, "BAD CONTENT" )
//      post.tell( GetContent( id ), clientProbe.ref )
//      clientProbe.expectMsgPF( max = 200.millis, hint = "changed contents" ){
//        case Envelope( payload: PostContent, h ) => payload mustBe content.copy( body = updated )
//      }
//    }
//
//    "follow happy path" taggedAs( HAPPY ) in { fixture: Fixture =>
//      import fixture._
//
//      val id = PostModule.nextId
//      val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )
//
//      PostModule.aggregateOf( id ) ! AddPost( id, content )
//      PostModule.aggregateOf( id ) ! ChangeBody( id, "new content" )
//      PostModule.aggregateOf( id ) ! Publish( id )
//
//      probe.expectMsgPF() {
//        case ReliableMessage( 1, Envelope( payload: PostAdded, _) ) => payload.content mustBe content
//      }
//
//      probe.expectMsgPF() {
//        case ReliableMessage( 2, Envelope( payload: BodyChanged, _) ) => payload.body mustBe "new content"
//      }
//
//      probe.expectMsgPF() {
//        case ReliableMessage( 3, Envelope( PostPublished( pid, _, title ), _) ) => {
//          pid mustBe id
//          title mustBe "Test Add"
//        }
//      }
//    }
  }
}
