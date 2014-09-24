package sample.blog.post

import akka.actor.{ActorPath, ActorRef, ActorSystem, Props}
import akka.contrib.pattern.ClusterSharding
import akka.event.LoggingReceive
import demesne._
import peds.akka.publish.{EventPublisher, ReliablePublisher}
import peds.commons.identifier._
import peds.commons.log.Trace
import sample.blog.author.AuthorListingModule
import shapeless._


trait PostModule extends AggregateRootModule {
  import sample.blog.post.PostModule.trace

  abstract override def start( ctx: Map[Symbol, Any] ): Unit = trace.block( "start" ) {
    super.start( ctx )
    PostModule.initialize( ctx )
  }
}

object PostModule extends AggregateRootModuleCompanion { module =>
  override val trace = Trace[PostModule.type]

  override val aggregateIdTag: Symbol = 'post


  override def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName

      override def aggregateRootProps: Props = trace.block( "aggregateRootProps" ) {
//        ClusterSharding( system ).shardRegion( AuthorListingModule.shardName ) //todo determine how to inject this during module start
        val authorListing = context( 'authorListing ).asInstanceOf[() => ActorRef]
        Post.props( this, authorListing() )
      }

      override val toString: String = shardName + "AggregateRootType"
    }
  }


  case class PostState(
    id: TaggedID[ShortUUID] = ShortUUID.nilUUID,
    content: PostContent = PostContent.empty,
    published: Boolean = false
  )

  object PostState {
    implicit val stateSpec = new AggregateStateSpecification[PostState] {
      implicit val postContentLabelledGen = LabelledGeneric[PostContent]
      private val bodyLens = lens[PostState] >> 'content >> 'body

      override def acceptance( state: PostState ): PartialFunction[Any, PostState] = {
        case PostAdded( id, c ) => PostState( id = id, content = c, published = false )
        case BodyChanged( _, body: String ) => bodyLens.set( state )( body )
        case _: PostPublished => state.copy( published = true )
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

    override var state: PostState = PostState()

    override def transitionFor( state: PostState ): Transition = {
      case _: PostAdded => context.become( around( created orElse publishProtocol orElse unhandled("CREATED") ) )
      case _: PostPublished => context.become( around( published orElse publishProtocol orElse unhandled("PUBLISHED") ) )
    }

    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = LoggingReceive {
      case GetContent(_)  => sender() ! state.content
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
    }

    val published: Receive = LoggingReceive {
      case GetContent(_) => sender() ! state.content
    }

    def unhandled( label: String ): Receive = {
      case x => log info s">>>>> POST[${label}] UNEXPECTED MESSAGE: $x"
    }
  }
}
