package sample.blog.post

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import com.typesafe.config.Config
import demesne._
import demesne.testkit.AggregateRootSpec
import demesne.testkit.concurrent.CountDownFunction
import org.scalatest.Tag
import omnibus.akka.envelope._
import omnibus.akka.publish.ReliablePublisher.ReliableMessage

import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import sample.blog.author.AuthorListingModule
import sample.blog.post.{ PostPrototol => P }

/**
  * Created by damonrolfs on 9/18/14.
  */
class PostModuleSpec extends AggregateRootSpec[Post, Post#ID] with ScalaFutures {

  override type State = Post
  override type ID = PostModule.ID
  override type Protocol = PostPrototol.type
  override val protocol: Protocol = PostPrototol

  override def createAkkaFixture(
    test: OneArgTest,
    system: ActorSystem,
    slug: String
  ): PostFixture = {
    new PostFixture( slug, system )
  }

  override type Fixture = PostFixture

  class PostFixture( _slug: String, _system: ActorSystem )
      extends AggregateFixture( _slug, _system ) {

    override val module: AggregateRootModule[State, ID] = PostModule

    val author: TestProbe = TestProbe()

    override def nextId(): TID = Post.identifying.next

    object TestPostRootType extends PostModule.PostType {
      override def repositoryProps( implicit model: DomainModel ): Props =
        PostModule.Repository.localProps( model )
    }

    override val rootTypes: Set[AggregateRootType] = Set( TestPostRootType )
//    override def resources: Map[Symbol, Any] = AuthorListingModule resources system
    override def resources: Map[Symbol, Any] = {
      val makeAuthorListing = () => author.ref
      Map( AuthorListingModule.ResourceKey -> makeAuthorListing )
    }
//    override def startTasks( system: ActorSystem ): Set[StartTask] = {
////      Set( StartTask( AuthorListingModule.startTask(system), "AuthorListing" ) )
//    }
//    override def context: Map[Symbol, Any] = trace.block( "context" ) {
//      val result = super.context
//      val makeAuthorListing = () => trace.block( "makeAuthorList" ){ author.ref }
//      result + ( 'authorListing -> makeAuthorListing )
//    }
  }

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
      system.eventStream.subscribe( bus.ref, classOf[PostPrototol.Event] )

      val id = nextId()
      val content = PostContent(
        author = "Damon",
        title = "Add Content",
        body = "add body content"
      )
      val post = PostModule aggregateOf id
      post !+ P.AddPost( id, content )
      bus.expectMsgPF( max = 3000.millis.dilated, hint = "post added" ) { //DMR: Is this sensitive to total num of tests executed?
        case payload: P.PostAdded => payload.content mustBe content
      }
    }

    "not respond before added" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[P.Event] )

      val id = nextId()
      val post = PostModule aggregateOf id
      post !+ P.ChangeBody( id, "dummy content" )
      post !+ P.Publish( id )
      bus.expectNoMessage( 200.millis.dilated )
    }

    "not respond to incomplete content" in { fixture: Fixture =>
      import fixture._

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[P.Event] )

      val id = nextId()
      val post = PostModule aggregateOf id
      post !+ P.AddPost( id, PostContent( author = "Damon", title = "", body = "no title" ) )
      bus.expectNoMessage( 200.millis.dilated )
      post !+ P.AddPost(
        id,
        PostContent( author = "", title = "Incomplete Content", body = "no author" )
      )
      bus.expectNoMessage( 200.millis.dilated )
    }

    "have empty contents before use" taggedAs WIP in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val post = PostModule aggregateOf id
      post.sendEnvelope( P.GetContent( id ) )( author.ref )
      author.expectMsgPF( max = 200.millis.dilated, hint = "empty contents" ) {
        case Envelope( payload: PostContent, h ) => {
          payload mustBe PostContent( "", "", "" )
          h.messageNumber mustBe MessageNumber( 2 )
          h.workId must not be WorkId.unknown
        }
      }
    }

    "have contents after posting" in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )

      val clientProbe = TestProbe()
      post !+ P.AddPost( id, content )
      post.sendEnvelope( P.GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "initial contents" ) {
        case Envelope( payload: PostContent, _ ) if payload == content => true
      }
    }

    "have changed contents after change" in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[P.Event] )

      val clientProbe = TestProbe()
      post !+ P.AddPost( id, content )
      bus.expectMsgPF( hint = "PostAdded" ) {
        case payload: P.PostAdded => payload.content mustBe content
      }

      post !+ P.ChangeBody( id, updated )
      bus.expectMsgPF( hint = "BodyChanged" ) {
        case payload: P.BodyChanged => payload.body mustBe updated
      }

      post.sendEnvelope( P.GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 200.millis.dilated, hint = "changed contents" ) {
        case Envelope( payload: PostContent, _ ) => payload mustBe content.copy( body = updated )
      }
    }

    "have changed contents after change and published" in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post !+ P.AddPost( id, content )
      post !+ P.ChangeBody( id, updated )
      post !+ P.Publish( id )
      post.sendEnvelope( P.GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ) {
        case Envelope( payload: PostContent, _ ) => payload mustBe content.copy( body = updated )
      }
    }

    "dont change contents after published" in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val post = PostModule aggregateOf id
      val content = PostContent( author = "Damon", title = "Contents", body = "initial contents" )
      val updated = "updated contents"

      val clientProbe = TestProbe()
      post !+ P.AddPost( id, content )
      post !+ P.ChangeBody( id, updated )
      post !+ P.Publish( id )
      post !+ P.ChangeBody( id, "BAD CONTENT" )
      post.sendEnvelope( P.GetContent( id ) )( clientProbe.ref )
      clientProbe.expectMsgPF( max = 400.millis.dilated, hint = "changed contents" ) {
        case Envelope( payload: PostContent, _ ) => payload mustBe content.copy( body = updated )
      }
    }

    "follow happy path" in { fixture: Fixture =>
      import fixture._

      val id = nextId()
      val content = PostContent( author = "Damon", title = "Test Add", body = "testing happy path" )

      system.eventStream.subscribe( bus.ref, classOf[ReliableMessage] )
      system.eventStream.subscribe( bus.ref, classOf[P.Event] )

      PostModule.aggregateOf( id ) !+ P.AddPost( id, content )
      PostModule.aggregateOf( id ) !+ P.ChangeBody( id, "new content" )
      PostModule.aggregateOf( id ) !+ P.Publish( id )

      bus.expectMsgPF( hint = "post-added" ) {
        case payload: P.PostAdded => payload.content mustBe content
      }

      bus.expectMsgPF( hint = "body-changed" ) {
        case payload: P.BodyChanged => payload.body mustBe "new content"
      }

      bus.expectMsgPF( hint = "post-published local" ) {
        case P.PostPublished( pid, _, title ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }

      author.expectMsgPF( hint = "post-published reliable" ) {
        case ReliableMessage( 1, Envelope( P.PostPublished( pid, _, title ), _ ) ) => {
          pid mustBe id
          title mustBe "Test Add"
        }
      }
    }

    "recorded in author index after post added via bus" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.rootType
      val ar = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'author )
      ar.isRight mustBe true
      for {
        register <- ar
      } {
        val id = nextId()
        val content =
          PostContent( author = "Damon", title = "Test Add", body = "testing author index add" )
        system.eventStream.subscribe( bus.ref, classOf[P.Event] )

        val post = PostModule.aggregateOf( id )
        post !+ P.AddPost( id, content )
        bus.expectMsgPF( hint = "post-added" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        val countDown = new CountDownFunction[String]
        countDown await 200.millis.dilated

        whenReady( register.futureGet( "Damon" ) ) { result =>
          result mustBe Some( id )
        }
        scribe.trace( s"""index:Damon = ${register.get( "Damon" )}""" )
        register.get( "Damon" ) mustBe Some( id )
      }
    }

    "recorded in title index after post added via event stream" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.rootType
      val ar = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'title )
      ar.isRight mustBe true
      for {
        register <- ar
      } {
        val p = TestProbe()

        val id = nextId()
        val content =
          PostContent( author = "Damon", title = "Test Add", body = "testing author index add" )
        system.eventStream.subscribe( bus.ref, classOf[P.Event] )
        system.eventStream.subscribe( p.ref, classOf[P.Event] )

        val post = PostModule.aggregateOf( id )
        post !+ P.AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        val countDown = new CountDownFunction[String]

        countDown await 200.millis.dilated
        whenReady( register.futureGet( "Test Add" ) ) { result =>
          result mustBe Some( id )
        }

        //      countDown await 75.millis.dilated
        register.get( "Test Add" ) mustBe Some( id )
      }
    }

    "withdrawn title in index after post delete via event stream" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.rootType
      val ar = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'author )
      ar.isRight mustBe true
      val tr = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'title )
      tr.isRight mustBe true
      for {
        authorRegister <- ar
        titleregister  <- tr
      } {
        val p = TestProbe()

        val id = nextId()

        val content =
          PostContent( author = "Damon", title = "Test Add", body = "testing index add" )
        system.eventStream.subscribe( bus.ref, classOf[P.Event] )
        system.eventStream.subscribe( p.ref, classOf[P.Event] )

        val post = PostModule.aggregateOf( id )
        post !+ P.AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( titleregister.futureGet( "Test Add" ) ) { result =>
          result mustBe Some( id )
        }

        //      countDown await 75.millis.dilated
        titleregister.get( "Test Add" ) mustBe Some( id )

        post !+ P.Delete( id )

        bus.expectMsgPF( hint = "post-deleted" ) {
          case payload: P.Deleted => payload.sourceId mustBe id
        }

        p.expectMsgPF( hint = "post-deleted stream" ) {
          case payload: P.Deleted => payload.sourceId mustBe id
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( titleregister.futureGet( "Test Add" ) ) { result =>
          scribe.error( s"HERE ****: result(Test Add) = $result" )
          result mustBe None
        }

        whenReady( authorRegister.futureGet( "Damon" ) ) { result =>
          scribe.error( s"HERE ****: result(Damon) = $result" )
          result mustBe None
        }
      }
    }

    "revised title in index after post title change via event stream" in { fixture: Fixture =>
      import fixture._

      val rt = PostModule.rootType
      val ar = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'author )
      ar.isRight mustBe true
      val tr = model.aggregateIndexFor[String, PostModule.TID, PostModule.TID]( rt, 'title )
      tr.isRight mustBe true
      for {
        authorRegister <- ar
        titleRegister  <- tr
      } {
        val p = TestProbe()

        val id = nextId()
        val content =
          PostContent( author = "Damon", title = "Test Add", body = "testing index add" )
        system.eventStream.subscribe( bus.ref, classOf[P.Event] )
        system.eventStream.subscribe( p.ref, classOf[P.Event] )

        val post = PostModule.aggregateOf( id )
        post !+ P.AddPost( id, content )

        bus.expectMsgPF( hint = "post-added" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        p.expectMsgPF( hint = "post-added stream" ) {
          case payload: P.PostAdded => payload.content mustBe content
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( authorRegister.futureGet( "Damon" ) ) { result =>
          result mustBe Some( id )
        }
        whenReady( titleRegister.futureGet( "Test Add" ) ) { result =>
          result mustBe Some( id )
        }

        //      countDown await 75.millis.dilated
        authorRegister.get( "Damon" ) mustBe Some( id )
        titleRegister.get( "Test Add" ) mustBe Some( id )

        post !+ P.ChangeTitle( id, "New Title" )

        bus.expectMsgPF( hint = "title-change" ) {
          case payload: P.TitleChanged => {
            payload.oldTitle mustBe "Test Add"
            payload.newTitle mustBe "New Title"
          }
        }

        p.expectMsgPF( hint = "post-title change stream" ) {
          case payload: P.TitleChanged => {
            payload.oldTitle mustBe "Test Add"
            payload.newTitle mustBe "New Title"
          }
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated

        whenReady( titleRegister.futureGet( "New Title" ) ) { result =>
          scribe.error( s"HERE ****: result(New Title) = $result" )
          result mustBe Some( id )
        }

        whenReady( titleRegister.futureGet( "Test Add" ) ) { result =>
          scribe.error( s"HERE ****: result(Test Add) = $result" )
          result mustBe None
        }

        whenReady( authorRegister.futureGet( "Damon" ) ) { result =>
          scribe.error( s"HERE ****: result(Damon) = $result" )
          result mustBe Some( id )
        }
      }
    }
  }
}
