package sample.blog

import akka.actor._
import akka.contrib.pattern.ClusterSharding
import akka.pattern.ask
import akka.persistence.journal.leveldb.{SharedLeveldbJournal, SharedLeveldbStore}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import demesne.DomainModel
import sample.blog.author.AuthorListingModule
import sample.blog.post.PostModule

import scala.concurrent.duration._


object BlogApp {
  def main( args: Array[String] ): Unit = {
    if ( args.isEmpty ) startup( Seq( 2551, 2552, 0 ) )
    else startup( args map { _.toInt } )
  }

  object registry extends AuthorListingModule with PostModule

  def startup( ports: Seq[Int] ): Unit = {
    ports foreach { port =>
      val config = ConfigFactory.parseString( "akka.remote.netty.tcp.port=" + port ).withFallback( ConfigFactory.load() )
      val clusterSystem = ActorSystem( "ClusterSystem", config )

      startSharedJournal(
        clusterSystem,
        startStore = ( port == 2551 ),
        path = ActorPath.fromString( "akka.tcp://ClusterSystem@127.0.0.1:2551/user/store" )
      )

      val makeAuthorListing: () => ActorRef = () => { ClusterSharding(clusterSystem).shardRegion(AuthorListingModule.shardName) }
      val model = DomainModel.register( "blog" )( clusterSystem )
      val context: Map[Symbol, Any] = Map(
        demesne.SystemKey -> clusterSystem,
        demesne.ModelKey -> model,
        demesne.FactoryKey -> demesne.factory.clusteredFactory,
        'authorListing -> makeAuthorListing
      )

      registry.start( context )

      // if ( port != 2551 && port != 2552 ) clusterSystem.actorOf( Bot.props( model ), "bot" )
      if ( port != 2551 && port != 2552 ) clusterSystem.actorOf( Bot.props( model ), "bot" )
    }

    def startSharedJournal( system: ActorSystem, startStore: Boolean, path: ActorPath ): Unit = {
      if ( startStore ) system.actorOf( Props[SharedLeveldbStore], "store" )

      import system.dispatcher
      implicit val timeout = Timeout( 1.minute )
      val f = ( system.actorSelection( path ) ? Identify( None ) )
      f.onSuccess {
        case ActorIdentity( _, Some(ref) ) => SharedLeveldbJournal.setStore( ref, system )
        case _ => {
          system.log.error( s"Shared journal not started at $path" )
          system.shutdown()
        }
      }
      f.onFailure {
        case _ => {
          system.log.error( s"Lookup of shared journal at ${path} timed out" )
          system.shutdown()
        }
      }
    }
  }
}
