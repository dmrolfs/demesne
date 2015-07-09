package contoso.conference.registration

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time.{Imports => joda}
import contoso.conference.ConferenceModule
import contoso.conference.payments.PaymentSourceModule
import contoso.conference.payments.PaymentSourceModule.PaymentCompleted
import contoso.conference.registration.OrderModule._
import contoso.conference.registration.SeatsAvailabilityModule.{CancelSeatReservation, CommitSeatReservation, MakeSeatReservation, SeatsReserved}
import contoso.registration.SeatQuantity
import demesne._
import demesne.AggregateRoot.Acceptance
import demesne.register.RegisterBus
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.log.Trace

import scala.concurrent.duration._


/**
* Represents a Saga that is in charge of communicating between the different distributed components when registering
* to a conference, reserving the seats, expiring the reservation in case the order is not completed in time, etc.
*
* Created by damonrolfs on 9/11/14.
*/
object RegistrationSagaModule extends SagaModule { module =>
  val trace = Trace[RegistrationSagaModule.type]
  // override val aggregateIdTag: Symbol = 'registration

  override val aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override def name: String = module.shardName

      override def aggregateRootProps( implicit model: DomainModel ): Props = {
        RegistrationSaga.props(
          meta = this,
          model = model,
          orderType = OrderModule.aggregateRootType,
          availabilityType = SeatsAvailabilityModule.aggregateRootType
        )
      }

      // override val toString: String = shardName + "AggregateRootType"
    }
  }


  case class ExpireRegistrationProcess( override val targetId: ExpireRegistrationProcess#TID ) extends Command

  case class RegistrationProcessExpired( override val sourceId: RegistrationProcessExpired#TID ) extends Event


  sealed trait ProcessState
  case object NotStarted extends ProcessState
  case object AwaitingReservationConfirmation extends ProcessState
  case object ReservationConfirmationReceived extends ProcessState
  case object PaymentConfirmationReceived extends ProcessState
  case object FullyConfirmed extends ProcessState
  case object OrderExpired extends ProcessState


  // Conference/Registration/RegistrationProcessManager.cs [41 - 88]
  case class RegistrationSagaState(
    id: TID,
    conferenceId: ConferenceModule.TID,
    orderId: OrderModule.TID,
    reservationId: OrderModule.TID,
    state: ProcessState = NotStarted,
    seatReservationWorkId: WorkId = WorkId.unknown,
    reservationAutoExpiration: Option[joda.DateTime]= None,
    lastUpdated: joda.DateTime = joda.DateTime.now
  ) {
    def isCompleted: Boolean = state == FullyConfirmed || state == OrderExpired
  }


  object RegistrationSaga {
    def props(
      meta: AggregateRootType,
      model: DomainModel,
      orderType: AggregateRootType,
      availabilityType: AggregateRootType
    ): Props = {
      Props( new RegistrationSaga( meta, model, orderType, availabilityType ) with EventPublisher )
    }

    //DMR: det where to locate this b/h; e.g., pull-req into nscala-time, peds?
    implicit def period2FiniteDuration( p: joda.Period ): FiniteDuration = FiniteDuration( p.getMillis, MILLISECONDS )
  }

  class RegistrationSaga(
    override val meta: AggregateRootType,
    override val model: DomainModel,
    orderType: AggregateRootType,
    seatsAvailabilityType: AggregateRootType
  ) extends Saga[RegistrationSagaState] {
    outer: EventPublisher =>

    override val trace = Trace( "RegistrationSaga", log )

    override var state: RegistrationSagaState = _

    import context.dispatcher
    var expirationMessager: Cancellable = _

    override def acceptance: Acceptance[RegistrationSagaState] = {
      case ( OrderPlaced(id, cid, seats, expiration, accessCode), state ) => {
        state.copy(
          conferenceId = cid,
          orderId = id,
          reservationId = id, // Use the order id as an opaque reservation id for the seat reservation. It could be anything else, as long as it is deterministic from the OrderPlaced event.
          state = AwaitingReservationConfirmation,
          reservationAutoExpiration = expiration,
          lastUpdated = joda.DateTime.now
        )
      }

      case ( (_: OrderUpdated, workId: WorkId), state ) => {
        state.copy( state = AwaitingReservationConfirmation, seatReservationWorkId = workId )
      }

      case ( _: SeatsReserved, state ) => state.copy( state = ReservationConfirmationReceived )
      case ( _: PaymentCompleted, state ) => state.copy( state = PaymentConfirmationReceived )
      case ( _: OrderConfirmed, state ) => state.copy( state = FullyConfirmed )
    }

    def order( id: Option[OrderModule.TID] ): ActorRef = OrderModule.aggregateOf( id )( model )

    def seatsAvailability( id: Option[SeatsAvailabilityModule.TID] ): ActorRef = {
      SeatsAvailabilityModule.aggregateOf( id )( model )
    }


    override def receiveCommand: Receive = around( notStarted orElse common )

    val notStarted: Receive = {
      case e @ OrderPlaced(oid, cid, seats, Some(expiration), accessCode) if expiration > joda.DateTime.now => {
        reserveSeats( conferenceId = cid, orderId = oid, seats = seats, expiration = Some(expiration) )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case e @ OrderPlaced(oid, cid, seats, None, accessCode) => {
        reserveSeats( conferenceId = cid, orderId = oid, seats = seats, expiration = None )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case OrderPlaced( orderId, _, _, _, _ ) => order( Some(orderId) ) ! RejectOrder( targetId = orderId )
    }

    val awaitingReservationConfirmation: Receive = {
      case c: OrderUpdated => orderUpdated( c )

      case c @ SeatsReserved(id, rid, details, availableSeatsChanged) if workId == state.seatReservationWorkId => {
        val markAsReserved = MarkSeatsAsReserved(
          targetId = state.orderId,
          seats = details.toSeq,
          expiration = state.reservationAutoExpiration
        )
        order( Some(state.orderId) ) ! markAsReserved
        context.become( around( reservationConfirmationReceived orElse common orElse confirmedUnhandled ) )
      }
    }

    val reservationConfirmationReceived: Receive = {
      case c: OrderUpdated => orderUpdated( c )
      case c: OrderConfirmed => orderConfirmed( c )
      case c @ PaymentCompleted( sourceId: PaymentCompleted#TID, paymentSourceId: PaymentSourceModule.TID ) => {
        order( Some(state.orderId) ) ! ConfirmOrder( state.orderId )
        context.become( around( paymentConfirmationReceived orElse common orElse confirmedUnhandled ) )
      }
    }

    val paymentConfirmationReceived: Receive = {
      case c: OrderConfirmed => orderConfirmed( c )
    }

    val confirmed: Receive = Actor.emptyBehavior

    val common: Receive = {
      case _: ExpireRegistrationProcess if state.state != FullyConfirmed => {
        state = state.copy( state = OrderExpired )
        order( Some(state.orderId) ) ! RejectOrder( state.orderId )
        val cancel = CancelSeatReservation( targetId = state.conferenceId, reservationId = state.reservationId )
        seatsAvailability( Some(state.conferenceId) ) ! cancel
      }

      case c @ SeatsReserved(id, rid, details, availableSeatsChanged) if workId != state.seatReservationWorkId => {
        log warning s"Seat reservation response for reservation id ${rid} does not match the expected work id."
      }
    }

    val confirmedUnhandled: Receive = {
      case c: SeatsReserved => {
        log.info(
          s"Seat reservation response for request [${workId}] for reservation id [${c.reservationId}] was already handled. " + 
          "Skipping event."
        )
      }
    }

    val expired: Receive = Actor.emptyBehavior

    def reserveSeats(
      conferenceId: ConferenceModule.TID,
      orderId: OrderModule.TID,
      seats: Seq[SeatQuantity],
      expiration: Option[joda.DateTime]
    ): Unit = {
      val reservation = MakeSeatReservation( targetId = conferenceId, reservationId = orderId, seats = seats )
      expiration foreach { exp =>
        val timeout = FiniteDuration( exp.getMillis - joda.DateTime.now.getMillis, MILLISECONDS )
        expirationMessager = context.system.scheduler.scheduleOnce( timeout ) {
          self !+ ExpireRegistrationProcess( state.id )
        }
      }
      seatsAvailability( Some(conferenceId) ) ! reservation
    }

    def orderUpdated( e: OrderUpdated ): Unit = {
      val reservation = MakeSeatReservation(
        targetId = state.conferenceId,
        reservationId = state.orderId,
        seats = e.seats
      )
//      state = state.copy( seatReservationWorkId = workId ) //DMR: isn't this cheating accept; how best to provide workId without closing on this in AR usage?
      seatsAvailability( Some(state.conferenceId) ) ! reservation
      accept( (e, workId) )
      context.become( around( awaitingReservationConfirmation orElse common ) )
    }

    def orderConfirmed( e: OrderConfirmed ): Unit = {
      val commit = CommitSeatReservation( targetId = state.conferenceId, reservationId = state.reservationId )
      seatsAvailability( Some(state.conferenceId) ) ! commit
      context.become( around( confirmed orElse confirmedUnhandled ))
    }

    override def unhandled( msg: Any ): Unit = ???
  }
}
