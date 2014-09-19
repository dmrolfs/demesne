package sample.blog

import akka.actor.{Actor, ActorLogging}
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding
import akka.event.LoggingReceive
import demesne.{AggregateRootRef, DomainModel}
import peds.commons.identifier._
import peds.commons.log.Trace
import sample.blog.author.AuthorListingModule
import sample.blog.post._

import scala.concurrent.duration._


object Bot {
  // def props( model: DomainModel ): Props = Props( new Bot( model ) )

  private case object Tick
}

class Bot extends Actor with ActorLogging {
  val trace = Trace[Bot]

  import context.dispatcher
  import sample.blog.Bot._
  val tickTask = context.system.scheduler.schedule( 3.seconds, 2.seconds, self, Tick )

  val model = DomainModel()( context.system )
  // val model =
  // val postRegion = ClusterSharding( context.system ).shardRegion( PostModule.shardName )
  def postRegion( id: ShortUUID ): AggregateRootRef = trace.block( s"postRegion( $id) " ) {
    val result = model.aggregateOf( PostModule.aggregateRootType, id )
    // log warning s"post AR = ${result}"
    result
  }

  val listingsRegion = ClusterSharding( context.system ).shardRegion( AuthorListingModule.shardName )

  val from = Cluster( context.system ).selfAddress.hostPort

  override def postStop(): Unit = {
    super.postStop()
    tickTask.cancel()
  }

  var n = 0
  val authors = Map( 0 -> "Patrik", 1 -> "Martin", 2 -> "Roland", 3 -> "Björn", 4 -> "Endre" )
  def currentAuthor = authors( n % authors.size )

  def receive = create

  import sample.blog.post.PostModule._

  val create: Receive = LoggingReceive {
    case Tick => {
      val postId = ShortUUID()
      n += 1
      log.info( s"bot CREATING post $n" )
      val title = s"Post $n from $from"
      postRegion( postId ) ! AddPost( postId, PostContent( currentAuthor, title, "..." ) )
      context become edit( postId )
    }
  }

  def edit( postId: PostModule.ID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot EDITING post $postId" )
      postRegion( postId ) ! ChangeBody( postId, "Something very interesting ..." )
      context become publish( postId )
    }
  }

  def publish( postId: PostModule.ID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot PUBLISHING post $postId" )
      postRegion( postId ) ! Publish( postId )
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
      log.info( s"""Posts by ${currentAuthor}: ${summaries.map{ _.title }.mkString( "\n\t", "\n\t", "" )}""" )
      context become create
    }
  }
}