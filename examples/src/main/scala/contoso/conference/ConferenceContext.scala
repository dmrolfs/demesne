package contoso.conference

import scala.concurrent._
import scala.concurrent.duration._
import scalaz.concurrent.Task
import akka.Done
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.pattern.pipe
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory
import bloomfilter.mutable.BloomFilter
import demesne.BoundedContext


//DMR: implement as ClusterSingleton

object ConferenceContextProtocol {
  sealed trait ConferenceContextMessage
  // case class HoldSlug( slug: String, conferenceId: ConferenceModule.TID ) extends ConferenceContext
  // case class SlugHeld( slug: String ) extends ConferenceContext
  case class ReserveSlug( slug: String, conferenceId: ConferenceModule.TID ) extends ConferenceContextMessage
  case class GetSlugStatus( slug: String ) extends ConferenceContextMessage

  sealed trait SlugStatus extends ConferenceContextMessage {
    def slug: String
  }

  case class SlugReserved( override val slug: String, conferenceId: ConferenceModule.TID ) extends SlugStatus {
    def toNotAvailable: SlugNotAvailable = SlugNotAvailable( slug, conferenceId )
  }
  case class SlugNotAvailable( override val slug: String, conferenceId: ConferenceModule.TID ) extends SlugStatus
  case class SlugAvailable( override val slug: String ) extends SlugStatus
  //conferences
  //orders
  //seattypes
  //orderseats
}


// implement to front a cache that subscribes to events
// use a bloomfilter behand
// initially use in-memory cache, but later use shard system

//todo replace with aggregate index: slug -> conferenceId
// Conference/Conference/ConferenceContext.cs
object ConferenceContext {
  val ResourceKey = Symbol( "ConferenceContext" )

  def startTask( system: ActorSystem ): Task[Done] = {
    Task {
      val confCtx = system.actorOf( props, "ConferenceContext" )
      BoundedContext( 'contoso ).withResources( Map( ResourceKey -> confCtx ) )
      Done
    }
  }

  def props: Props = Props( new ConferenceContext )

  // val shardName: String = "PricingRetrievers"

  val fallback = "context-timeout = 250ms"
  val config = ConfigFactory.load
                .getConfig( "contoso.conference.registration" )
                .withFallback( ConfigFactory.parseString( fallback ) )

  import java.util.concurrent.TimeUnit
  val contextTimeout = Duration( config.getDuration( "context-timeout", TimeUnit.MILLISECONDS ), MILLISECONDS )
}

class ConferenceContext extends Actor with ActorLogging {
  import ConferenceContext._

  implicit val ec: ExecutionContext = context.system.dispatchers.lookup( "conference-context-dispatcher" )

  // initialize with basic and upon start populate via PeristentView
  val filter: BloomFilter[String] = BloomFilter[String]( 100, 0.05 )

  // use ScalaCache to front shared cache?
  var slugCache: Map[String, ConferenceModule.TID] = Map()  // temp in-memory rep; future should be shared cache server

  import contoso.conference.{ ConferenceProtocol => CP }
  import contoso.conference.{ ConferenceContextProtocol => CCP }

  override def receive: Receive = LoggingReceive {
    // DMR: LISTEN TO AKKA EVENT BUS for this
    case CP.ConferenceCreated( sourceId, conference ) => {
      val result = addSlug( conference.slug, sourceId )
      result pipeTo sender()
    }

    case CCP.ReserveSlug( slug, conferenceId ) => {
      val result = addSlug( slug, conferenceId )
      result pipeTo sender()
    }

    case CCP.GetSlugStatus( slug ) => {
      val result = if ( filter.mightContain( slug ) ) {
        findSlug( slug ) map { fs => fs getOrElse CCP.SlugAvailable( slug ) }
      } else {
        Future successful { CCP.SlugAvailable( slug ) }
      }

      result pipeTo sender()
    }

    // case ConferenceContextTimeout => sendResponseAndShutdown( Future successful { ConferenceContextTimeout } )
  }

  //DMR: With refactor to external service consider using CircuitBreaker
  def addSlug( slug: String, conferenceId: ConferenceModule.TID ): Future[CCP.SlugStatus] = {
    for {
      status: Option[CCP.SlugReserved] <- findSlug( slug )
    } yield {
      status.fold[CCP.SlugStatus] {
        slugCache += ( slug -> conferenceId )
        filter add slug
        CCP.SlugReserved( slug, conferenceId )
      } {
        _.toNotAvailable
      }
    }
  }

  //DMR: With refactor to external service consider using CircuitBreaker
  //DMR: and use separate execetioncontext:
  //DMR: implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(new ForkJoinPool())
  def findSlug( slug: String ): Future[Option[CCP.SlugReserved]] = Future successful {
    slugCache get slug map { CCP.SlugReserved( slug, _ ) }
  }

  // def sendResponseAndShutdown( response: Future[Any] ): Unit = {
  //   response pipeTo originalSender
  //   log debug s"shutting down ${getClass.safeSimpleName} on: ${response}"
  //   context stop self
  // }

  // import context.dispatcher
  // val timeoutMessager = context.system.scheduler.scheduleOnce( conferenceTimeout ) {
  //   self ! ConferenceContextTimeout
  // }
}



// class ConferenceContext extends Actor with ActorLogging {
//   import ConferenceContext._

//   override def receive: Receive = LoggingReceive {
//     case CalculateTotal( conferenceId, seatItems ) => {
//       val originalSender = sender
//       val handler = context.actorOf( CalculationHandler.props( seatItems, originalSender ) )
//       val conference = model.aggregateOf( conferenceRootType, conferenceId )
//       conference.tell( ConferenceModule.GetPublishedSeatTypes, handler )
//     }
//   }
// }