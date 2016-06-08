package contoso.conference.registration

import scala.util.Random
import akka.actor._
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time.{Imports => joda}
import com.typesafe.config.ConfigFactory
import contoso.conference.ConferenceModule
import contoso.registration.{OrderLine, SeatQuantity}
import demesne._
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.identifier.ShortUUID
import squants._


object OrderModule extends AggregateRootModule[ShortUUID] { module =>
  import com.wix.accord._
  import com.wix.accord.dsl._
  import peds.commons.log.Trace

  val fallback = "reservation-auto-expiration = 15 minutes"
  val config = ConfigFactory.load
    .getConfig( "contoso.conference.registration" )
    .withFallback( ConfigFactory.parseString( fallback ) )

  import java.util.concurrent.{TimeUnit => TU}
  val reservationAutoExpiration: joda.Period = joda.Period.millis(
    config.getDuration( "reservation-auto-expiration", TU.MILLISECONDS ).toInt
  )

  override val trace = Trace[OrderModule.type]

  // override val aggregateIdTag: Symbol = 'order

  override val rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = {
        Order.props(
          model,
          this,
          ClusterSharding( model.system ).shardRegion( PricingRetriever.shardName )
        )
      }

      // override val toString: String = name + "AggregateRootType"
    }
  }


  // Conference/Registration/Commands/RegsiterToConference.cs
  case class RegisterToConference(
    override val targetId: RegisterToConference#TID,
    conferenceId: ConferenceModule.TID,
    seats: Seq[SeatQuantity]
  ) extends Command

  object RegisterToConference {
    implicit val SeatQuantityValidator = validator[SeatQuantity] { sq =>
      sq.quantity should be > Each( 0 )
    }

    implicit val seatsAvailableValidator = validator[Seq[SeatQuantity]] { sqs =>
      sqs is notEmpty
      sqs.each is valid
    }

    implicit val registerToConferenceValidator = validator[RegisterToConference] { rtc =>
      rtc.seats is valid
    }
  }


  // Conference/Registration/Commands/MarkSeatsAsReserved.cs
  case class MarkSeatsAsReserved(
    override val targetId: MarkSeatsAsReserved#TID,
    seats: Seq[SeatQuantity],
    expiration: Option[joda.DateTime]
  ) extends Command

  // Conference/Registration/Commands/RejectOrder.cs
  case class RejectOrder( override val targetId: RejectOrder#TID ) extends Command

  // Conference/Registration/Commands/AssignRegistrantDetails.cs
  case class AssignRegistrantDetails(
    override val targetId: AssignRegistrantDetails#TID,
    firstName: String,    //DMR: better to model as PersonName archetype
    lastName: String,
    email: String
  ) extends Command

  object AssignRegistrantDetails {
    implicit val assignRegistrantDetailsValidator = validator[AssignRegistrantDetails] { ard =>
      ard.firstName as "registrant first name" is notEmpty
      ard.lastName as "registrant last name" is notEmpty
      ard.email as "registrant email" is notEmpty
      ard.email as "registrant email" must matchRegex( """[\w-]+(\.?[\w-])*\@[\w-]+(\.[\w-]+)+""".r )
    }
  }

  // Conference/Registration/Commands/ConfirmOrder.cs
  case class ConfirmOrder( override val targetId: ConfirmOrder#TID ) extends Command


  // Registration.Contracts/Events/OrderPlaced.cs
  case class OrderPlaced(
    override val sourceId: OrderPlaced#TID,
    conferenceId: ConferenceModule.TID,
    seats: Seq[SeatQuantity],
    reservationAutoExpiration: Option[joda.DateTime],
    accessCode: String
  ) extends Event

  // Registration.Contracts/Events/OrderUpdated.cs
  case class OrderUpdated(
    override val sourceId: OrderUpdated#TID,
    seats: Seq[SeatQuantity]
  ) extends Event

  // Registration.Contracts/Events/OrderTotalsCalculated.cs
  case class OrderTotalsCalculated(
    override val sourceId: OrderTotalsCalculated#TID,
    total: Money,  //DMR: Money?
    lines: Seq[OrderLine],
    isFreeOfCharge: Boolean
  ) extends Event

  // Registration.Contracts/Events/OrderPartiallyReserved.cs
  case class OrderPartiallyReserved(
    override val sourceId: OrderPartiallyReserved#TID,
    reservationExpiration: Option[joda.DateTime],
    seats: Seq[SeatQuantity]
  ) extends Event

  // Registration.Contracts/Events/OrderReservationCompleted.cs
  case class OrderReservationCompleted(
    override val sourceId: OrderReservationCompleted#TID,
    reservationExpiration: Option[joda.DateTime],
    seats: Seq[SeatQuantity]
  ) extends Event

  // Registration.Contracts/Events/OrderExpired.cs
  case class OrderExpired( override val sourceId: OrderExpired#TID ) extends Event

  // Registration.Contracts/Events/OrderRegistrantAssigned.cs
  case class OrderRegistrantAssigned(
    override val sourceId: OrderRegistrantAssigned#TID,
    firstName: String,    //DMR: better to model as PersonName archetype
    lastName: String,
    email: String
  ) extends Event

  // Registration.Contracts/Events/OrderConfirmed.cs
  case class OrderConfirmed( override val sourceId: OrderConfirmed#TID ) extends Event

  //DMR: Need to determine how to handle migrate of deprecated events into new; e.g., Mapper / Migrations
  case class OrderPaymentConfirmed( override val sourceId: OrderPaymentConfirmed#TID ) extends Event

  object OrderPaymentConfirmed {
    implicit def migrate( e: OrderPaymentConfirmed ): OrderConfirmed = OrderConfirmed( e.sourceId )
  }


  // Conference/Registration/Order.cs
  case class OrderState(
    id: TID,
    conferenceId: ConferenceModule.TID,
    seats: Seq[SeatQuantity] = Seq(),
    confirmed: Boolean = false
  ) {
    def isCompletedBy( reserved: Seq[SeatQuantity] ): Boolean = {
      seats exists { s =>
        if ( s.quantity == Each( 0 ) ) false
        else reserved exists { r => ( r.seatTypeId == s.seatTypeId ) && ( r.quantity == s.quantity ) }
      }
    }
  }


  object Order {
    def props( model: DomainModel, rt: AggregateRootType, pricingRetriever: ActorRef ): Props = {
      Props( new Order( model, rt, pricingRetriever ) with EventPublisher )
    }
  }

  class Order(
    override val model: DomainModel,
    override val rootType: AggregateRootType,
    pricingRetriever: ActorRef
  ) extends AggregateRoot[OrderState] { outer: EventPublisher =>
    override val trace = Trace( "Order", log )

    override var state: OrderState = _
    var expirationMessager: Cancellable = _

    override def acceptance: Acceptance = {
      case ( OrderPlaced(_, conferenceId, seats, _, _ ), state ) => state.copy( conferenceId = conferenceId, seats = seats )
      case ( OrderUpdated(_, seats), state ) => state.copy( seats = seats )
      case ( OrderPartiallyReserved(_, _, seats), state ) => state.copy( seats = seats )
      case ( OrderReservationCompleted(_, _, seats), state ) => state.copy( seats = seats )
      case ( OrderConfirmed, state ) => state.copy( confirmed = true )
      case ( OrderPaymentConfirmed, state ) => state.copy( confirmed = true )
    }

    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = common orElse LoggingReceive {
      // Conference/Registration/Order.cs[88 - 102]
      case c @ RegisterToConference( orderId, conferenceId, seats ) if validate( c ) == Success => {
        val expiration = reservationAutoExpiration.later
        persist( OrderPlaced( orderId, conferenceId, seats, Some(expiration), generateHandle ) ) { event =>
          accept( event )
          pricingRetriever ! PricingRetriever.CalculateTotal( conferenceId, seats )
          publish( event )
          context.become( around( reserved orElse common ) )
        }
      }
    }

    def reserved: Receive = LoggingReceive {
      // Conference/Registration/Handlers/OrderCommandHandler.cs[39]
      // Conference/Registration/Order.cs[115-122]
      // no need to convertItems
      case c @ RegisterToConference( orderId, conferenceId, seats ) if validate( c ) == Success => {
        persist( OrderUpdated( orderId, seats ) ) { event =>
          accept( event )
          pricingRetriever ! PricingRetriever.CalculateTotal( conferenceId, seats )
          publish( event )
        }
      }

      // Conference/Registration/Handlers/OrderCommandHandler.cs[39]
      // Conference/Registration/Order.cs[124]
      case MarkSeatsAsReserved( orderId, reserved, expiration ) if state.isCompletedBy( reserved ) => {
        val completed = OrderReservationCompleted(
          sourceId = orderId,
          reservationExpiration = expiration,
          seats = reserved
        )
        persist( completed ) { event => acceptAndPublish( event ) }
      }

      // Conference/Registration/Handlers/OrderCommandHandler.cs[39]
      // Conference/Registration/Order.cs[124]
      case MarkSeatsAsReserved( orderId, reserved, expiration ) => {
        val partiallyReserved = OrderPartiallyReserved(
          sourceId = orderId,
          reservationExpiration = expiration,
          seats = reserved
        )

        persist( partiallyReserved ) { event =>
          accept( event )
          pricingRetriever ! PricingRetriever.CalculateTotal( state.conferenceId, reserved )
          publish( event )
        }
      }

      // Conference/Registration/Handlers/OrderCommandHandler.cs[62]
      // Conference/Registration/Order.cs[145]
      case RejectOrder( orderId ) => persist( OrderExpired( orderId ) ) { e => 
        acceptAndPublish( e ) 
        context.become( around( expired ) )
      }

      // Conference/Registration/Handlers/OrderCommandHandler.cs[73]
      // Conference/Registration/Order.cs[145]
      case AssignRegistrantDetails( orderId, firstName, lastName, email ) => {
        persist( OrderRegistrantAssigned( orderId, firstName, lastName, email ) ) { e => acceptAndPublish( e ) }
      }

      // Conference/Registration/Handlers/OrderCommandHandler.cs[80]
      // Conference/Registration/Order.cs[153]
      case ConfirmOrder( orderId ) => persist( OrderConfirmed( orderId ) ) { e => 
        acceptAndPublish( e ) 
        context.become( around( confirmed orElse common ) )
      }
    }

    def confirmed: Receive = Actor.emptyBehavior

    def expired: Receive = Actor.emptyBehavior

    def common: Receive = LoggingReceive {
      // Conference/Registration/Order.cs[88 - 102]
      case c @ PricingRetriever.OrderTotal( lines: Seq[OrderLine], total: Money ) => {
        val totalCalculated = OrderTotalsCalculated(
          sourceId = state.id,
          total = total,
          lines = lines,
          isFreeOfCharge = ( total == 0D )
        )

        persist( totalCalculated ) { e => acceptAndPublish( e ) }
      }
    }

    // Conference/Conference.Common/Utils/HandleGenerator.cs
    private def generateHandle: String = Random.alphanumeric.take( 6 ).mkString.capitalize
  }
}

