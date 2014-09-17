package contoso.conference

import scala.concurrent._
import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.pattern.{ pipe }
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory


//DMR: implement as ClusterSingleton

// implement to front a cache that subscribes to events
// use a bloomfilter behand
// initially use in-memory cache, but later use shard system

// Conference/Conference/ConferenceContext.cs
object ConferenceContext {
  def props: Props = Props( new ConferenceContext )

  // val shardName: String = "PricingRetrievers"

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


  val fallback = "context-timeout = 250ms"
  val config = ConfigFactory.load
                .getConfig( "contoso.conference.registration" )
                .withFallback( ConfigFactory.parseString( fallback ) )

  import java.util.concurrent.TimeUnit
  val contextTimeout = Duration( config.getDuration( "context-timeout", TimeUnit.MILLISECONDS ), MILLISECONDS )
}

class ConferenceContext extends Actor with ActorLogging {
  import ConferenceContext._
  import peds.commons.collection.BloomFilter

  implicit val ec: ExecutionContext = context.system.dispatchers.lookup( "conference-context-dispatcher" )

  // initialize with basic and upon start populate via PeristentView
  var filter: BloomFilter[String] = BloomFilter()

  // use ScalaCache to front shared cache?
  var slugCache: Map[String, ConferenceModule.TID] = Map()  // temp in-memory rep; future should be shared cache server

  override def receive: Receive = LoggingReceive {
    // DMR: LISTEN TO AKKA EVENT BUS for this
    case ConferenceModule.ConferenceCreated( sourceId, conference ) => {
      val result = addSlug( conference.slug, sourceId )
      result pipeTo sender
    }

    case ReserveSlug( slug, conferenceId ) => {
      val result = addSlug( slug, conferenceId )
      result pipeTo sender
    }

    case GetSlugStatus( slug ) => {
      val result = if ( filter.has_?( slug ) ) {
        findSlug( slug ) map { fs => fs getOrElse SlugAvailable( slug ) }
      } else {
        Future successful { SlugAvailable( slug ) }
      }

      result pipeTo sender
    }

    // case ConferenceContextTimeout => sendResponseAndShutdown( Future successful { ConferenceContextTimeout } )
  }

  //DMR: With refactor to external service consider using CircuitBreaker
  def addSlug( slug: String, conferenceId: ConferenceModule.TID ): Future[SlugStatus] = {
    for {
      status: Option[SlugReserved] <- findSlug( slug )
    } yield {
      status.fold[SlugStatus] {
        slugCache += ( slug -> conferenceId )
        filter += slug
        SlugReserved( slug, conferenceId )
      } {
        _.toNotAvailable
      }
    }
  }

  //DMR: With refactor to external service consider using CircuitBreaker
  //DMR: and use separate execetioncontext:
  //DMR: implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(new ForkJoinPool())
  def findSlug( slug: String ): Future[Option[SlugReserved]] = Future successful {
    slugCache get slug map { SlugReserved( slug, _ ) }
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