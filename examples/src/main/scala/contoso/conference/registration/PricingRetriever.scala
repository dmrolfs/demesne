package contoso.conference.registration

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory
import squants.market._
import demesne.{ AggregateRootType, DomainModel }
import contoso.conference.ConferenceModule
import contoso.registration.{SeatOrderLine, OrderLine, SeatQuantity}


object PricingRetriever {
  def props( model: DomainModel ): Props = Props( new PricingRetriever( model ) )

  val shardName: String = "PricingRetrievers"

  sealed trait PricingMessage

  case class CalculateTotal( conferenceId: ConferenceModule.ID, seatItems: Seq[SeatQuantity] ) extends PricingMessage

  // Conference.Registration/OrderTotal.cs
  case class OrderTotal( lines: Seq[OrderLine], total: Money ) extends PricingMessage

  case object ConferencePublishedSeatTypesTimeout extends PricingMessage

  lazy val conferenceRootType: AggregateRootType = ConferenceModule.aggregateRootType

  val fallback = "conference-timeout = 250ms"
  val config = ConfigFactory.load
                .getConfig( "contoso.conference.registration.pricing" )
                .withFallback( ConfigFactory.parseString( fallback ) )

  import java.util.concurrent.TimeUnit
  val conferenceTimeout = Duration( config.getDuration( "conference-timeout", TimeUnit.MILLISECONDS ), MILLISECONDS )


  object CalculationHandler {
    def props( seatItems: Seq[SeatQuantity], originalSender: ActorRef ): Props = {
      Props( new CalculationHandler( seatItems, originalSender ) )
    }
  }

  // Conference/Registration/PricingService.cs
  class CalculationHandler( seatItems: Seq[SeatQuantity], originalSender: ActorRef ) extends Actor with ActorLogging {
    override def receive: Receive = LoggingReceive {
      case ConferenceModule.SeatTypes( seatTypes ) => {
        val lines = for {
          i <- seatItems
          t <- seatTypes find { _.id == i.seatTypeId }
        } yield SeatOrderLine( seatTypeId = i.seatTypeId, unitPrice = t.price, quantity = i.quantity )

        val total = lines.foldLeft( USD(0) )( _ + _.total )

        sendResponseAndShutdown( OrderTotal( lines, total ) )
      }

      case ConferencePublishedSeatTypesTimeout => sendResponseAndShutdown( ConferencePublishedSeatTypesTimeout )
    }

    def sendResponseAndShutdown( response: Any ): Unit = {
      originalSender ! response
      log debug s"shutting down CalculationHandler on: ${response}"
      context stop self
    }

    import context.dispatcher
    val timeoutMessager = context.system.scheduler.scheduleOnce( conferenceTimeout ) {
      self ! ConferencePublishedSeatTypesTimeout
    }
  }
}


class PricingRetriever( model: DomainModel ) extends Actor with ActorLogging {
  import PricingRetriever._

  override def receive: Receive = LoggingReceive {
    case CalculateTotal( conferenceId, seatItems ) => {
      val originalSender = sender
      val handler = context.actorOf( CalculationHandler.props( seatItems, originalSender ) )
      val conference = model.aggregateOf( conferenceRootType, conferenceId )
      conference.tell( ConferenceModule.GetPublishedSeatTypes, handler )
    }
  }
}