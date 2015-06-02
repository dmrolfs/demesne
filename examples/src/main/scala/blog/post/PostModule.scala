package sample.blog.post

import scala.concurrent.{ ExecutionContext, Future }
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import akka.persistence.AtLeastOnceDelivery
import demesne._
import demesne.register.local.RegisterLocalAgent
import demesne.register.{ContextChannelSubscription, FinderSpec, RegisterBus, RegisterBusSubscription}
import peds.akka.envelope.Envelope
import peds.akka.publish.EventPublisher
import peds.commons.V
import peds.commons.identifier._
import peds.commons.log.Trace
import scalaz._, Scalaz._
import shapeless._
import shapeless.syntax.typeable._


object PostModule extends AggregateRootModule with InitializeAggregateRootClusterSharding { module =>
  override val trace = Trace[PostModule.type]

  var makeAuthorListing: () => ActorRef = _

  override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    context: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : V[Future[Unit]] = trace.block( s"initializer($rootType, $model, $context)" ) {

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

  private def checkAuthorList( context: Map[Symbol, Any] ): V[() => ActorRef] = {
    val result = for {
      al <- context get 'authorListing
      r <- scala.util.Try[() => ActorRef]{ al.asInstanceOf[() => ActorRef] }.toOption
    } yield r.successNel

    result getOrElse UnspecifiedMakeAuthorListError( 'authorListing ).failureNel 
  }

  // override val aggregateIdTag: Symbol = 'post


  override def aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = PostActor.props( model, this, makeAuthorListing )

      override def finders: Seq[FinderSpec[_, _]] = {
        Seq(
          RegisterLocalAgent.spec[String, PostModule.TID]( 'author, RegisterBusSubscription /* not reqd - default */ ) {
            case PostAdded( sourceId, PostContent(author, _, _) ) => (author, sourceId)
          },
          RegisterLocalAgent.spec[String, PostModule.TID]( 'title, ContextChannelSubscription( classOf[PostAdded] ) ) {
            case PostAdded( sourceId, PostContent(_, title, _) ) => (title, sourceId)
          }
        )
      }
    }
  }


  object PostActor {
    def props( model: DomainModel, meta: AggregateRootType, makeAuthorListing: () => ActorRef ): Props = trace.block(s"props(_,$meta, $makeAuthorListing)") {
      import peds.akka.publish._

      Props(
        new PostActor( model, meta ) with ReliablePublisher with AtLeastOnceDelivery {
          trace( s"POST CTOR makeAuthorListing = $makeAuthorListing" )
          val authorListing: ActorRef = makeAuthorListing()

          import peds.commons.util.Chain._

          override def publish: Publisher = trace.block( "publish" ) {
            val bus = RegisterBus.bus( model.registerBus, meta )( _: FinderSpec[_,_] )
            val buses = meta.finders
                          .filter( _.relaySubscription == RegisterBusSubscription )
                          .foldLeft( silent ){ _ +> bus(_) }
            buses +> stream +> filter +> reliablePublisher( authorListing.path )
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

    implicit val stateSpecification = new AggregateStateSpecification[State] {
      private val bodyLens = lens[State] >> 'content >> 'body

      override def acceptance( state: State ): PartialFunction[Any, State] = {
        case PostAdded( id, c ) => State( id = id, content = c, published = false )
        case BodyChanged( _, body: String ) => bodyLens.set( state )( body )
        case _: PostPublished => state.copy( published = true )
      }
    }
  }


  class PostActor( model: DomainModel, override val meta: AggregateRootType ) extends AggregateRoot[PostActor.State] {
    outer: EventPublisher =>

    import PostActor._

    override val trace = Trace( "Post", log )

    // override val registerBus: RegisterBus = model.registerBus

    override var state: State = State()

    override def transitionFor( state: State ): Transition = {
      case _: PostAdded => context become around( created )
      case _: PostPublished => context become around( published )
    }

    override def receiveCommand: Receive = around( quiescent )

    import peds.akka.envelope._

    val quiescent: Receive = LoggingReceive {
      case GetContent(_)  => sender() !+ state.content
      case AddPost( id, content ) if !content.isIncomplete  => trace.block( s"quiescent(AddPost(${id}, ${content}))" ) {
        persist( PostAdded( id, content ) ) { event =>
          trace.block( s"persist(${event})" ) {
            trace( s"before accept state = ${state}" )
            state = accept( event )
            trace( s"after accept state = ${state}" )
            log info s"New post saved: ${state.content.title}"
            trace.block( s"publish($event)" ) { publish( event ) }
          }
        }
      }
    }

    val created: Receive = LoggingReceive {
      case GetContent( id ) => sender() !+ state.content

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
      case GetContent(_) => sender() !+ state.content
    }
  }


  final case class UnspecifiedMakeAuthorListError private[post]( expectedKey: Symbol )
  extends IllegalArgumentException( s"AuthorList factory function required at initialization property [$expectedKey]" )
}
