package sample.blog

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor._
import akka.pattern.ask
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging
import demesne._
import sample.blog.author.AuthorListingModule
import sample.blog.post.PostModule

object BlogApp extends StrictLogging {

  def main( args: Array[String] ): Unit = {
    import ExecutionContext.Implicits.global
    implicit val timeout = Timeout( 5.seconds )
    if (args.isEmpty) startup( Seq( 2551, 2552, 0 ) )
    else startup( args map { _.toInt } )
  }

  def startup( ports: Seq[Int] )( implicit ec: ExecutionContext, timeout: Timeout ): Unit = {
    ports foreach { port =>
      val config = ConfigFactory
        .parseString( "akka.remote.netty.tcp.port=" + port )
        .withFallback( ConfigFactory.load() )
      implicit val clusterSystem = ActorSystem( "ClusterSystem", config )

      startSharedJournal(
        clusterSystem,
        startStore = (port == 2551),
        path = ActorPath.fromString( "akka.tcp://ClusterSystem@127.0.0.1:2551/user/store" )
      )

      for {
        zero <- BoundedContext.make(
          key = 'blog,
          configuration = config,
          rootTypes = Set( PostModule.rootType ) //,
//          userResources = AuthorListingModule.resources(clusterSystem)
        )
        built = zero.withStartTask( AuthorListingModule.startTask )
        started <- built.start()
        model   <- started.futureModel
      } {
        logger.info( s"bounded context [{}] started: [{}]", started.name, started )
        if (port != 2551 && port != 2552) clusterSystem.actorOf( Bot.props( model ), "bot" )
      }
    }

    def startSharedJournal( system: ActorSystem, startStore: Boolean, path: ActorPath ): Unit = {
      if (startStore) system.actorOf( Props[SharedLeveldbStore], "store" )

      import system.dispatcher
      implicit val timeout = Timeout( 1.minute )
      val f = (system.actorSelection( path ) ? Identify( None ))
      f onComplete {
        case Success( ActorIdentity( _, Some( ref ) ) ) =>
          SharedLeveldbJournal.setStore( ref, system )
        case Success( x ) => {
          system.log.error(
            "Shared journal not started after timeout at [{}]: [{}]",
            path,
            x.toString
          )
          system.terminate()
        }
        case Failure( ex ) => {
          system.log.error( ex, "Shared journal not started at [{}]", path )
          system.terminate()
        }
      }
    }
  }
}
