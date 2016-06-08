package sample.blog.post

import scala.concurrent.{ ExecutionContext, Future }
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import akka.persistence.AtLeastOnceDelivery
import demesne._
import demesne.register.local.RegisterLocalAgent
import demesne.register._
import peds.akka.envelope.Envelope
import peds.akka.publish.{ EventPublisher, StackableStreamPublisher }
import peds.commons.Valid
import peds.commons.identifier._
import peds.commons.log.Trace
import scalaz._, Scalaz._
import shapeless._
import shapeless.syntax.typeable._


object PostModule extends AggregateRootModule[ShortUUID] with InitializeAggregateRootClusterSharding { module =>
  override val trace = Trace[PostModule.type]

  var makeAuthorListing: () => ActorRef = _

  override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    context: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : Valid[Future[Unit]] = trace.block( s"initializer($rootType, $model, $context)" ) {

//todo: I need to better determine how to support validation+Future or Task, esp to ensure order of operation and dev model.
// I'm not confident this impl will always work for more complex scenarios since I haven't combined the local V[Future] with
// the return of super.initializer

    val result = checkAuthorList( context ) map { al => 
      Future successful {
        makeAuthorListing = al
        trace( s"makeAuthorListing = $makeAuthorListing .... sample result [${makeAuthorListing()}]" )
      }
    }

    super.initializer( rootType, model, context )
  }

  private def checkAuthorList( context: Map[Symbol, Any] ): Valid[() => ActorRef] = {
    val result = for {
      al <- context get 'authorListing
      r <- scala.util.Try[() => ActorRef]{ al.asInstanceOf[() => ActorRef] }.toOption
    } yield r.successNel[Throwable]

    result getOrElse Validation.failureNel( UnspecifiedMakeAuthorListError('authorListing) )
  }

  // override val aggregateIdTag: Symbol = 'post


  override def rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = PostActor.props( model, this, makeAuthorListing )

      override def indexes: Seq[AggregateIndexSpec[_, _]] = {
        Seq(
          RegisterLocalAgent.spec[String, PostModule.TID]( 'author, RegisterBusSubscription /* not reqd - default */ ) {
            case PostAdded( sourceId, PostContent(author, _, _) ) => Directive.Record(author, sourceId)
            case Deleted( sourceId ) => Directive.Withdraw( sourceId )
          },
          RegisterLocalAgent.spec[String, PostModule.TID]( 'title, ContextChannelSubscription( classOf[PostAdded] ) ) {
            case PostAdded( sourceId, PostContent(_, title, _) ) => Directive.Record(title, sourceId)
            case TitleChanged( sourceId, oldTitle, newTitle ) => Directive.Revise( oldTitle, newTitle )
            case Deleted( sourceId ) => Directive.Withdraw( sourceId )
          }
        )
      }
    }
  }


  object PostActor {
    def props( model: DomainModel, rt: AggregateRootType, makeAuthorListing: () => ActorRef ): Props = trace.block(s"props(_,${rt}, $makeAuthorListing)") {
      import peds.akka.publish._

      Props(
        new PostActor( model, rt )
        with ReliablePublisher  
        with StackableRegisterBusPublisher 
        with StackableStreamPublisher 
        with AtLeastOnceDelivery {
          trace( s"POST CTOR makeAuthorListing = $makeAuthorListing" )
          val authorListing: ActorRef = makeAuthorListing()

          import peds.commons.util.Chain._

          override def publish: Publisher = trace.block( "publish" ) {
            super.publish +> filter +> reliablePublisher( authorListing.path )
          }

          val filter: Publisher = {
            case e @ Envelope( _: PostPublished, _ ) => Left( e )
            case _ => Right( () )
          }
        }
      )
    }

    case class State(
      id: TaggedID[ShortUUID] = ShortUUID.nilUUID,
      content: PostContent = PostContent.empty,
      published: Boolean = false
    )

    object State {
      val bodyLens = lens[State] >> 'content >> 'body
      val titleLens = lens[State] >> 'content >> 'title
    }
  }


  class PostActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[PostActor.State] {
    outer: EventPublisher =>

    import PostActor._

    override val trace = Trace( "Post", log )

    override var state: State = State()

    override val acceptance: Acceptance = {
      case ( PostAdded(id, c), _ )=> State( id = id, content = c, published = false )
      case ( BodyChanged(_, body: String), state ) => State.bodyLens.set( state )( body )
      case ( TitleChanged(_, _, newTitle), state ) => State.titleLens.set( state )( newTitle )
      case ( _: PostPublished, state ) => state.copy( published = true )
      case ( _: Deleted, _ ) => State()
    }

    override def receiveCommand: Receive = around( quiescent )

    import peds.akka.envelope._

    val quiescent: Receive = LoggingReceive {
      case GetContent(_)  => sender() !+ state.content
      case AddPost( id, content ) if !content.isIncomplete  => trace.block( s"quiescent(AddPost(${id}, ${content}))" ) {
        persist( PostAdded( id, content ) ) { event =>
          trace.block( s"persist(${event})" ) {
            trace( s"before accept state = ${state}" )
            accept( event )
            trace( s"after accept state = ${state}" )
            log info s"New post saved: ${state.content.title}"
            trace.block( s"publish($event)" ) { publish( event ) }
          }

          context become around( created )
        }
      }
    }

    val created: Receive = LoggingReceive {
      case GetContent( id ) => sender() !+ state.content

      case ChangeBody( id, body ) => persist( BodyChanged( id, body ) ) { event =>
        accept( event )
        log info s"Post changed: ${state.content.title}"
        publish( event )
      }

      case ChangeTitle( id, newTitle ) => persist( TitleChanged(id, state.content.title, newTitle) ) { event => 
        acceptAndPublish( event ) 
      }

      case Publish( postId ) => {
        persist( PostPublished( postId, state.content.author, state.content.title ) ) { event =>
          accept( event )
          log info s"Post published: ${state.content.title}"
          publish( event )
          context become around( published )
        }
      }

      case Delete( id ) => persist( Deleted(id) ) { event => 
        acceptAndPublish( event ) 
        context become around( quiescent )
      }
    }

    val published: Receive = LoggingReceive {
      case GetContent(_) => sender() !+ state.content
    }
  }


  final case class UnspecifiedMakeAuthorListError private[post]( expectedKey: Symbol )
  extends IllegalArgumentException( s"AuthorList factory function required at initialization property [$expectedKey]" )
}
