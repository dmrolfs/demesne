package sample.blog.post

import scala.reflect._
import akka.Done
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import akka.persistence.AtLeastOnceDelivery

import scalaz._
import Scalaz._
import shapeless._
import omnibus.akka.envelope.Envelope
import omnibus.akka.publish.EventPublisher
import omnibus.commons.{TryV, Valid}
import omnibus.commons.identifier._
import omnibus.commons.log.Trace
import demesne._
import demesne.index.local.IndexLocalAgent
import demesne.index._
import demesne.repository.AggregateRootRepository.{ClusteredAggregateContext, LocalAggregateContext}
import demesne.repository.{AggregateRootRepository, EnvelopingAggregateRootRepository}
import sample.blog.post.{PostPrototol => P}


case class Post(
  id: TaggedID[ShortUUID],
  content: PostContent = PostContent.empty,
  published: Boolean = false,
  isActive: Boolean = true
) {
  type ID = ShortUUID
  type TID = TaggedID[ID]
}

object Post {
  val bodyLens = lens[Post] >> 'content >> 'body
  val titleLens = lens[Post] >> 'content >> 'title

  implicit val identifying = new Identifying[Post] with ShortUUID.ShortUuidIdentifying[Post] {
    override val idTag: Symbol = 'post
    override def tidOf( p: Post ): TID = p.id
    //      override val evID: ClassTag[ID] = classTag[ShortUUID]
    //      override def idOf( o: State ): TID = o.id
    //      override def fromString( idstr: String ): ID = ShortUUID( idstr )
    //      override def nextId: TryV[TID] = tag( ShortUUID() ).right
    //      override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]
  }
}


object PostModule extends AggregateRootModule[Post, Post#ID] { module =>
  private val trace = Trace[PostModule.type]

//  override type ID = ShortUUID
//  override def nextId: TryV[TID] = identifying.nextTID

  override val rootType: AggregateRootType = new PostType

  class PostType extends AggregateRootType {
    override val name: String = module.shardName

//    override lazy val identifying: Identifying[_] = PostActor.postIdentifying

    override def repositoryProps( implicit model: DomainModel ): Props = Repository.clusteredProps( model )

    override def indexes: Seq[IndexSpecification] = {
      Seq(
        IndexLocalAgent.spec[String, PostModule.TID, PostModule.TID]( 'author, IndexBusSubscription /* not reqd - default */ ) {
          case P.PostAdded( sourceId, PostContent(author, _, _) ) => Directive.Record(author, sourceId, sourceId)
          case P.Deleted( sourceId ) => Directive.Withdraw( sourceId )
        },
        IndexLocalAgent.spec[String, PostModule.TID, PostModule.TID]( 'title, ContextChannelSubscription( classOf[P.Event] ) ) {
          case P.PostAdded( sourceId, PostContent(_, title, _) ) => Directive.Record( title, sourceId, sourceId)
          case P.TitleChanged( sourceId, oldTitle, newTitle ) => Directive.ReviseKey( oldTitle, newTitle )
          case P.Deleted( sourceId ) => Directive.Withdraw( sourceId )
        }
      )
    }
  }


  object Repository {
    def localProps( model: DomainModel ): Props = Props( new Repository(model) with LocalAggregateContext )
    def clusteredProps( model: DomainModel ): Props = Props( new Repository(model) with ClusteredAggregateContext )
  }

  abstract class Repository( model: DomainModel )
  extends EnvelopingAggregateRootRepository( model, module.rootType ) { actor: AggregateRootRepository.AggregateContext =>
    import sample.blog.author.AuthorListingModule

    import demesne.repository.{ StartProtocol => SP }

    var makeAuthorListing: () => ActorRef = _

    override def doLoad(): SP.Loaded = {
      logger.info( "loading" )
      SP.Loaded( rootType, dependencies = Set(AuthorListingModule.ResourceKey) )
    }

    override def doInitialize( resources: Map[Symbol, Any] ): Valid[Done] = {
      checkAuthorListing( resources ) map { al =>
        logger.info( "initializing makeAuthorListing:[{}]", al )
        makeAuthorListing = al
        Done
      }
    }

    override def aggregateProps: Props = trace.block( "aggregateProps" ) {
//      throw new IllegalArgumentException( "LOOK AT FN STACK" )
      log.debug( "PostModule: making PostActor Props with model:[{}] rootType:[{}] makeAuthorListing:[{}]", model, rootType, makeAuthorListing )
      PostActor.props( model, rootType, makeAuthorListing )
    }

    private def checkAuthorListing( resources: Map[Symbol, Any] ): Valid[() => ActorRef] = trace.block("checkAuthListing()") {
      log.debug( "resources[{}] = [{}]", AuthorListingModule.ResourceKey, resources get AuthorListingModule.ResourceKey )
      val result = for {
        alValue <- resources get AuthorListingModule.ResourceKey
        al <- Option( alValue )
        r <- scala.util.Try[() => ActorRef]{ al.asInstanceOf[() => ActorRef] }.toOption
      } yield r.successNel[Throwable]

      log.debug( "[{}] resource result = [{}]", AuthorListingModule.ResourceKey, result )
      result getOrElse Validation.failureNel( UnspecifiedMakeAuthorListError(AuthorListingModule.ResourceKey) )
    }
  }

  object PostActor {
    def props( model: DomainModel, rt: AggregateRootType, makeAuthorListing: () => ActorRef ): Props = trace.block(s"props(_,${rt}, $makeAuthorListing)") {
      import omnibus.akka.publish._

      Props(
        new PostActor( model, rt )
        with ReliablePublisher
        with StackableIndexBusPublisher
        with StackableStreamPublisher
        with AtLeastOnceDelivery {
          log.debug( "POST CTOR makeAuthorListing = [{}]", makeAuthorListing)
          lazy val authorListing: ActorRef = makeAuthorListing()
          log.debug( "POST CTOR authorListing = [{}]", authorListing )

          import omnibus.commons.util.Chain._

          override def publish: Publisher = trace.block( "publish" ) {
            super.publish +> filter +> reliablePublisher( authorListing.path )
          }

          val filter: Publisher = {
            case e @ Envelope( _: P.PostPublished, _ ) => logger.info("PASSED TO RELIABLE_PUBLISH:[{}]", e); Left( e )
            case e: P.PostPublished => logger.info("PASSED TO RELIABLE_PUBLISH:[{}]", e); Left( e )
            case x => logger.info("blocked from reliable_publish:[{}]", x.toString); Right( () )
          }

        }
      )
    }
  }


  class PostActor(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[Post, Post#ID] with AggregateRoot.Provider { outer: EventPublisher =>
    import PostActor._

    private val trace = Trace( "Post", log )

//    override def tidFromPersistenceId(idstr: String ): TID = {
//      val identifying = implicitly[Identifying[State]]
//      identifying.safeParseId[ID]( idstr )( classTag[ShortUUID] )
//    }

    override var state: Post = _
//    override val evState: ClassTag[State] = ClassTag( classOf[State] )

    override val acceptance: Acceptance = {
      case ( P.PostAdded(id, c), _ )=> Post( id = id, content = c, published = false )
      case ( P.BodyChanged(_, body: String), state ) => Post.bodyLens.set( state )( body )
      case ( P.TitleChanged(_, _, newTitle), state ) => Post.titleLens.set( state )( newTitle )
      case ( _: P.PostPublished, state ) => state.copy( published = true )
      case ( _: P.Deleted, s ) if Option(s).nonEmpty => s.copy( isActive = false )
    }

    override def receiveCommand: Receive = LoggingReceive { around( quiescent ) }

    import omnibus.akka.envelope._

    val quiescent: Receive = {
      case P.GetContent(_)  => sender() !+ Option(state).map{ _.content }.getOrElse{ PostContent.empty }
      case P.AddPost( id, content ) if !content.isIncomplete  => trace.block( s"quiescent(AddPost(${id}, ${content}))" ) {
        persist( P.PostAdded( id, content ) ) { event =>
          trace.block( s"persist(${event})" ) {
            trace( s"before accept state = ${state}" )
            accept( event )
            trace( s"after accept state = ${state}" )
            log info s"New post saved: ${state.content.title}"
            trace.block( s"publish($event)" ) { publish( event ) }
          }

          context become LoggingReceive { around( created ) }
        }
      }
    }

    val created: Receive = {
      case P.GetContent( id ) => sender() !+ state.content

      case P.ChangeBody( id, body ) => persist( P.BodyChanged( id, body ) ) { event =>
        accept( event )
        log info s"Post changed: ${state.content.title}"
        publish( event )
      }

      case P.ChangeTitle( id, newTitle ) => persist( P.TitleChanged(id, state.content.title, newTitle) ) { acceptAndPublish }

      case P.Publish( postId ) => {
        persist( P.PostPublished( postId, state.content.author, state.content.title ) ) { event =>
          accept( event )
          log info s"Post published: ${state.content.title}"
          publish( event )
          context become LoggingReceive { around( published ) }
        }
      }

      case P.Delete( id ) => persist( P.Deleted(id) ) { event =>
        acceptAndPublish( event ) 
        context become LoggingReceive { around( quiescent ) }
      }
    }

    val published: Receive = {
      case P.GetContent(_) => sender() !+ state.content
    }
  }


  final case class UnspecifiedMakeAuthorListError private[post]( expectedKey: Symbol )
  extends IllegalArgumentException( s"AuthorList factory function required at initialization property [$expectedKey]" )
}
