package sample.blog

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.cluster.Cluster
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import demesne.DomainModel
import omnibus.identifier._
import sample.blog.author.AuthorListingModule
import sample.blog.post._

object Bot {
  def props( model: DomainModel ): Props = Props( new Bot( model ) )

  private case object Tick
}

class Bot( model: DomainModel ) extends Actor with ActorLogging {

  import context.dispatcher
  import sample.blog.Bot._
  val tickTask = context.system.scheduler.schedule( 3.seconds, 2.seconds, self, Tick )

  // val model =
  // val postRegion = ClusterSharding( context.system ).shardRegion( PostModule.shardName )
  def postRegion( id: Post#TID ): ActorRef = model.aggregateOf( PostModule.rootType, id.value )

  val listingsRegion =
    ClusterSharding( context.system ).shardRegion( AuthorListingModule.shardName )

  val from = Cluster( context.system ).selfAddress.hostPort

  override def postStop(): Unit = {
    super.postStop()
    tickTask.cancel()
  }

  var n = 0
  val authors = Map( 0 -> "Patrik", 1 -> "Martin", 2 -> "Roland", 3 -> "Björn", 4 -> "Endre" )
  def currentAuthor = authors( n % authors.size )

  def receive = create

  import sample.blog.post.{ PostPrototol => P }

  val create: Receive = LoggingReceive {
    case Tick => {
      //      val postId = TryV unsafeGet Post.identifying.nextTID
      val postId: Id.Aux[Post, ShortUUID] = Post.identifying.next
      n += 1
      log.info( s"bot CREATING post $n" )
      val title = s"Post $n from $from"
      postRegion( postId ) ! P.AddPost( postId, PostContent( currentAuthor, title, "..." ) )
      context become edit( postId )

//      val addPost = for {
//        postId <- Post.identifying.nextTID
//      } yield {
//        n += 1
//        log.info( s"bot CREATING post $n" )
//        val title = s"Post $n from $from"
//        postRegion( postId ) ! P.AddPost( postId, PostContent( currentAuthor, title, "..." ) )
//        context become edit( postId )
//      }
//
//      addPost match {
//        case \/-(_) => ()
//        case -\/( ex ) => throw ex
//      }
    }
  }

  def edit( postId: PostModule.TID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot EDITING post $postId" )
      postRegion( postId ) ! P.ChangeBody( postId, "Something very interesting ..." )
      context become publish( postId )
    }
  }

  def publish( postId: PostModule.TID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot PUBLISHING post $postId" )
      postRegion( postId ) ! P.Publish( postId )
      context become list
    }
  }

  val list: Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot LISTING posts by $currentAuthor" )
      listingsRegion ! AuthorListingModule.GetPosts( currentAuthor )
    }

    case AuthorListingModule.Posts( summaries ) => {
      log.info( s"bot LISTING recd posts by $currentAuthor" )
      log.info( s"""Posts by ${currentAuthor}: ${summaries
        .map { _.title }
        .mkString( "\n\t", "\n\t", "" )}""" )
      context become create
    }
  }
}
