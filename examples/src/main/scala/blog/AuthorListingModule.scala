package sample.blog.author

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, ReceiveTimeout }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.event.LoggingReceive

import com.typesafe.scalalogging.LazyLogging
import demesne.{ BoundedContext, StartTask }
import omnibus.akka.envelope.EnvelopingActor
import omnibus.akka.publish.ReliableReceiver
import omnibus.commons.log.Trace
import sample.blog.post.PostPrototol.PostPublished

object AuthorListingModule extends LazyLogging {
  val trace = Trace[AuthorListingModule.type]

  val ResourceKey = 'AuthorListing

  def resources( system: ActorSystem ): Map[Symbol, Any] =
    Map( ResourceKey -> makeAuthorListing( system ) )

  //  def startTask( system: ActorSystem ): BoundedContext => Done = { bc: BoundedContext =>
  val startTask: StartTask = {
    StartTask.withFunction( "start Author Listing Module" ) { bc: BoundedContext =>
      val system = bc.system
      ClusterSharding( system ).start(
        typeName = AuthorListingModule.shardName,
        entityProps = AuthorListing.props,
        settings = ClusterShardingSettings( system ),
        extractEntityId = AuthorListing.idExtractor,
        extractShardId = AuthorListing.shardResolver
      )

      Map( ResourceKey -> makeAuthorListing( system ) )
    //    Done
    }
  }

  def makeAuthorListing( implicit system: ActorSystem ): () => ActorRef = () => {
    ClusterSharding( system ) shardRegion AuthorListingModule.shardName
  }

  val shardName: String = "AuthorListings"

  case class GetPosts( author: String )

  case class Posts( list: immutable.IndexedSeq[PostPublished] )

  object AuthorListing {

    import omnibus.akka.envelope.Envelope
    import omnibus.akka.publish.ReliablePublisher.ReliableMessage

    def props: Props = Props[AuthorListing]

    val idExtractor: ShardRegion.ExtractEntityId = {
      case p: PostPublished => ( p.author, p )
      case m: GetPosts      => ( m.author, m )

      //DMR: abstract these into complementing trait
      case e @ Envelope( payload, _ ) if idExtractor.isDefinedAt( payload ) =>
        ( idExtractor( payload )._1, e )
      case r @ ReliableMessage( _, msg ) if idExtractor.isDefinedAt( msg ) =>
        ( idExtractor( msg )._1, r )
    }

    val shardResolver: ShardRegion.ExtractShardId = {
      case PostPublished( _, author, _ ) => {
        // logger info s"AuthorListing.shardResolver: POST_PUBLISHED recognized: ${( math.abs( author.hashCode ) % 100 )}"
        (math.abs( author.hashCode ) % 100).toString
      }

      case GetPosts( author ) => {
        // logger info s"AuthorListing.shardResolver: GET_POSTS recognized: ${( math.abs( author.hashCode ) % 100 )}"
        (math.abs( author.hashCode ) % 100).toString
      }

      //DMR: abstract these into complementing trait
      //DMR: hopefully shardResolver will become a partialfunction to make that easier
      case Envelope( payload, _ )  => shardResolver( payload )
      case ReliableMessage( _, m ) => shardResolver( m )
    }
  }

  class AuthorListing extends Actor with EnvelopingActor with ReliableReceiver with ActorLogging {
    def trace: Trace[_] = Trace[AuthorListing]

    log debug s"STARTED AUTHOR_LISTING: ${self.path}"
    context setReceiveTimeout 2.minutes

    var posts: immutable.IndexedSeq[PostPublished] = Vector.empty

    override def receive: Receive = around {
      LoggingReceive {
        case p: PostPublished => {
          log debug s"AUTHOR_LISTING. REGULAR PostPublished recd: ${p}   SENDER=${sender()}"
          posts :+= p
          log info s"Post added to ${p.author}'s list: ${p.title}"
          log debug s"""AUTHOR_LISTING: posts updated to: ${posts.mkString( "[", ",", "]" )}"""
        }

        case GetPosts( _ ) => {
          log debug s"""AUTHOR_LISTING:GetPosts. posts = ${posts.mkString( "[", ",", "]" )}"""
          sender() ! Posts( posts )
        }

        case ReceiveTimeout => context.parent ! ShardRegion.Passivate( stopMessage = PoisonPill )
      }
    }

    override def unhandled( unexpected: Any ): Unit = {
      log debug s"AUTHOR LISTING: UNEXPECTED MESSAGE: $unexpected"
    }
  }
}
