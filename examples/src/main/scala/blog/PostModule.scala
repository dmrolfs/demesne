package sample.blog.post

import scala.concurrent.duration._
import akka.actor.{ ActorContext, ActorPath, ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.contrib.pattern.{ ClusterSharding, ShardRegion }
import akka.persistence.{ SnapshotOffer }
import com.typesafe.scalalogging.LazyLogging
import shapeless._
import peds.akka.publish.{ EventPublisher, ReliablePublisher }
import peds.commons.log.Trace
import peds.commons.module._
import peds.commons.identifier._
import peds.commons.util._
import demesne._
import sample.blog.author.AuthorListingModule


trait PostModule extends AggregateRootModule {
  import PostModule.trace

  abstract override def start( ctx: Map[Symbol, Any] ): Unit = trace.block( "start" ) {
    super.start( ctx )

    PostModule.initialize( ctx )
    val model = ctx( 'model ).asInstanceOf[DomainModel]
    implicit val system = PostModule.system
    val rootType = PostModule.aggregateRootType
    startClusterShard( rootType )
    model.registerAggregateType( rootType, demesne.factory.clusteredFactory )
  }
}

object PostModule extends AggregateRootModuleCompanion { module =>
  val trace = Trace[PostModule.type]

  // val shardName: String = "Posts"
  override val aggregateIdTag: Symbol = 'post

  override def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName

      override def aggregateRootProps: Props = {
        Post.props(
          this,
          ClusterSharding( system ).shardRegion( AuthorListingModule.shardName )
        )
      }

      override val toString: String = shardName + "AggregateRootType"
    }
  }

  sealed trait Command extends CommandLike {
    override type ID = module.ID
  }

  case class AddPost( override val targetId: AddPost#TID, content: PostContent ) extends Command
  case class GetContent( override val targetId: GetContent#TID ) extends Command
  case class ChangeBody( override val targetId: ChangeBody#TID, body: String ) extends Command
  case class Publish( override val targetId: Publish#TID ) extends Command


  sealed trait Event extends EventLike {
    override type ID = module.ID
    override val sourceTypeName: Option[String] = Option( module.aggregateRootType.name )
  }

  case class PostAdded( override val sourceId: PostAdded#TID, content: PostContent ) extends Event
  case class BodyChanged( override val sourceId: BodyChanged#TID, body: String ) extends Event
  case class PostPublished( sourceId: PostPublished#TID, author: String, title: String ) extends Event


  case class PostContent( author: String, title: String, body: String ) {
    def isIncomplete: Boolean = author.isEmpty || title.isEmpty
  }

  object PostContent {
    val empty = PostContent( "", "", "" )
  }


  case class PostState( id: TaggedID[ShortUUID], content: PostContent, published: Boolean ) {
    // def this( id: ShortUUID, content: PostContent, published: Boolean ) = {
    //   this( TaggedID( 'post, id ), content, published )
    // }
  }

  object PostState {
    // def apply( id: ShortUUID, content: PostContent, published: Boolean ): PostState = {
    //   new PostState( id, content, published )
    // }

    implicit val stateSpec = new AggregateStateSpecification[PostState] {
      implicit val postContentLabelledGen = LabelledGeneric[PostContent]
      private val bodyLens = lens[PostState] >> 'content >> 'body

      override def acceptance( state: PostState ): PartialFunction[Any, PostState] = {
        // case PostAdded( _, c ) => logger.info(s"PostState.ACCEPTING PostAdded"); state.copy( content = c )
        case PostAdded( id, c ) => logger.info(s"PostState.ACCEPTING PostAdded"); PostState( id = id, content = c, published = false )
        case BodyChanged( _, body: String ) => logger.info(s"PostState.ACCEPTING BodyChanged"); bodyLens.set( state )( body )
        case _: PostPublished => logger.info(s"PostState.ACCEPTING PostPublished"); state.copy( published = true )
      }
    }
  }


  object Post {
    def props( meta: AggregateRootType, authorListing: ActorRef ): Props = {
      Props(
        new Post( meta ) with ReliablePublisher {
          override def destination: ActorPath = authorListing.path
        }
      )
    }
  }


  class Post( override val meta: AggregateRootType ) extends AggregateRoot[PostState] { outer: EventPublisher =>
    override val trace = Trace( "Post", log )

    import Post._

    // override protected var state: PostState = PostState( ShortUUID.nilUUID, PostContent.empty, false )
    override var state: PostState = _

    override def transitionFor( state: PostState ): Transition = {
      case _: PostAdded => context.become( around( created orElse publishProtocol orElse unhandled("CREATED") ) )
      case _: PostPublished => context.become( around( published orElse publishProtocol orElse unhandled("PUBLISHED") ) )
    }

    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = LoggingReceive {
      case GetContent(_) => sender() ! state.content
      case AddPost( id, content ) => {
        if ( !content.isIncomplete ) {
          persist( PostAdded( id, content ) ) { event =>
            state = accept( event )
            log info s"New post saved: ${state.content.title}"
            publish( event )
          }
        }
      }
    }

    val created: Receive = LoggingReceive {
      case GetContent( id ) => sender() ! state.content

      case ChangeBody( id, body ) => persist( BodyChanged( id, body ) ) { event =>
        state = accept( event )
        log info s"Post changed: ${state.content.title}"
        publish( event )
      }

      case Publish( postId ) => {
        persist( PostPublished( postId, state.content.author, state.content.title ) ) { event =>
          state = accept( event )
          log info s"Post published: ${state.content.title}"
          publish( event )
        }
      }

      // case e: envelope.Envelope => receiveCommand( e )
    }

    val published: Receive = LoggingReceive {
      case GetContent(_) => sender() ! state.content
    }

    def unhandled( label: String ): Receive = {
      case x => log info s">>>>> POST[${label}] UNEXPECTED MESSAGE: $x"
    }
  }
}
