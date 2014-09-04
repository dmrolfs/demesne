package sample.blog

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding
import peds.commons.identifier._
import peds.commons.log.Trace
import demesne.{ AggregateRootRef, DomainModel }
import author.AuthorListingModule
import post.PostModule


object Bot {
  // def props( model: DomainModel ): Props = Props( new Bot( model ) )
  
  private case object Tick
}

class Bot extends Actor with ActorLogging {
  val trace = Trace[Bot]

  import Bot._
  import context.dispatcher
  val tickTask = context.system.scheduler.schedule( 3.seconds, 3.seconds, self, Tick )

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

  import PostModule._

  val create: Receive = LoggingReceive {
    case Tick => {
      val postId = ShortUUID()
      n += 1
      log.info( s"bot CREATING post $n" )
      val title = s"Post $n from $from"
      postRegion( postId ) ! PostModule.AddPost( postId, PostContent( currentAuthor, title, "..." ) )
      context become edit( postId )
    }
  }

  def edit( postId: PostModule.ID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot EDITING post $postId" )
      postRegion( postId ) ! PostModule.ChangeBody( postId, "Something very interesting ..." )
      context become publish( postId )
    }
  }

  def publish( postId: PostModule.ID ): Receive = LoggingReceive {
    case Tick => {
      log.info( s"bot PUBLISHING post $postId" )
      postRegion( postId ) ! PostModule.Publish( postId )
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