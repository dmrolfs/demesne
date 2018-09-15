package contoso.conference.registration

import scala.concurrent.duration._
import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time.{ Imports => joda }
import contoso.conference.ConferenceModule
import contoso.conference.payments.PaymentSourceModule
import contoso.registration.SeatQuantity
import demesne._
import demesne.repository._
import omnibus.akka.envelope.{ WorkId, _ }
import omnibus.akka.publish.EventPublisher
import omnibus.identifier._

object RegistrationSagaProtocol
    extends AggregateProtocol[RegistrationSagaState, RegistrationSagaState#ID] {
  case class ExpireRegistrationProcess( override val targetId: ExpireRegistrationProcess#TID )
      extends Command
  case class RegistrationProcessExpired( override val sourceId: RegistrationProcessExpired#TID )
      extends Event
}

// Conference/Registration/RegistrationProcessManager.cs [41 - 88]
case class RegistrationSagaState(
  id: RegistrationSagaState#TID,
  conferenceId: ConferenceModule.TID,
  orderId: OrderModule.TID,
  reservationId: OrderModule.TID,
  state: RegistrationSagaState.ProcessState = RegistrationSagaState.NotStarted,
  seatReservationWorkId: WorkId = WorkId.unknown,
  reservationAutoExpiration: Option[joda.DateTime] = None,
  lastUpdated: joda.DateTime = joda.DateTime.now
) {
  type ID = RegistrationSagaState.identifying.ID
  type TID = RegistrationSagaState.identifying.TID

  def isCompleted: Boolean =
    state == RegistrationSagaState.FullyConfirmed || state == RegistrationSagaState.OrderExpired
}

object RegistrationSagaState {
  implicit val identifying = Identifying.byShortUuid[RegistrationSagaState]

  sealed trait ProcessState
  case object NotStarted extends ProcessState
  case object AwaitingReservationConfirmation extends ProcessState
  case object ReservationConfirmationReceived extends ProcessState
  case object PaymentConfirmationReceived extends ProcessState
  case object FullyConfirmed extends ProcessState
  case object OrderExpired extends ProcessState
}

/**
  * Represents a Saga that is in charge of communicating between the different distributed components when registering
  * to a conference, reserving the seats, expiring the reservation in case the order is not completed in time, etc.
  *
  * Created by damonrolfs on 9/11/14.
  */
object RegistrationSagaModule extends SagaModule[RegistrationSagaState, RegistrationSagaState#ID] {
  module =>

  object Repository {
    def props( model: DomainModel ): Props = Props( new Repository( model ) )
  }

  class Repository( model: DomainModel )
      extends EnvelopingAggregateRootRepository( model, rootType )
      with ClusteredAggregateContext {
    override def aggregateProps: Props = {
      RegistrationSaga.props(
        rootType = RegistrationSagaType,
        model = model,
        orderType = OrderModule.rootType,
        availabilityType = SeatAssignmentsModule.rootType
      )
    }
  }

  object RegistrationSagaType extends AggregateRootType {
    override def name: String = module.shardName
    override type S = RegistrationSagaState
    override def repositoryProps( implicit model: DomainModel ): Props = Repository.props( model )
  }

  override val rootType: AggregateRootType = RegistrationSagaType

  object RegistrationSaga {

    def props(
      rootType: AggregateRootType,
      model: DomainModel,
      orderType: AggregateRootType,
      availabilityType: AggregateRootType
    ): Props = {
      Props(
        new RegistrationSaga( rootType, model, orderType, availabilityType ) with EventPublisher
      )
    }

    import scala.language.implicitConversions

    //DMR: det where to locate this b/h; e.g., pull-req into nscala-time, omnibus?
    implicit def period2FiniteDuration( p: joda.Period ): FiniteDuration = {
      FiniteDuration( p.getMillis.toLong, MILLISECONDS )
    }
  }

  class RegistrationSaga(
    override val rootType: AggregateRootType,
    override val model: DomainModel,
    orderType: AggregateRootType,
    seatsAvailabilityType: AggregateRootType
  ) extends Saga[RegistrationSagaState, ShortUUID]
      with AggregateRoot.Provider { outer: EventPublisher =>
    import contoso.conference.registration.{ RegistrationSagaProtocol => RS }
    import contoso.conference.registration.{ OrderProtocol => O }
    import contoso.conference.registration.{ SeatsAvailabilityProtocol => SA }
    import contoso.conference.payments.{ PaymentSourceProtocol => P }
    import RegistrationSagaState._

    override var state: RegistrationSagaState = _

    import context.dispatcher
    var expirationMessager: Cancellable = _

    override def acceptance: Acceptance = {
      case ( O.OrderPlaced( id, cid, _, expiration, _ ), s ) => {
        s.copy(
          conferenceId = cid,
          orderId = id,
          reservationId = id, // Use the order id as an opaque reservation id for the seat reservation. It could be anything else, as long as it is deterministic from the OrderPlaced event.
          state = AwaitingReservationConfirmation,
          reservationAutoExpiration = expiration,
          lastUpdated = joda.DateTime.now
        )
      }

      case ( ( _: O.OrderUpdated, WorkId( workId ) ), s ) => {
        s.copy(
          state = AwaitingReservationConfirmation,
          seatReservationWorkId = WorkId( workId )
        )
      }

      case ( _: SA.SeatsReserved, s )   => s.copy( state = ReservationConfirmationReceived )
      case ( _: P.PaymentCompleted, s ) => s.copy( state = PaymentConfirmationReceived )
      case ( _: O.OrderConfirmed, s )   => s.copy( state = FullyConfirmed )
    }

    def order( id: Option[OrderModule.TID] ): ActorRef = {
      OrderModule.aggregateOf( id getOrElse ShortUUID() )( model )
    }

    def seatsAvailability( id: Option[SeatsAvailabilityModule.TID] ): ActorRef = {
      SeatsAvailabilityModule.aggregateOf( id getOrElse ShortUUID() )( model )
    }

    override def receiveCommand: Receive = around( notStarted orElse common )

    val notStarted: Receive = {
      case e @ O.OrderPlaced( oid, cid, seats, Some( expiration ), _ )
          if expiration > joda.DateTime.now => {
        reserveSeats(
          conferenceId = cid,
          orderId = oid,
          seats = seats,
          expiration = Some( expiration )
        )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case e @ O.OrderPlaced( oid, cid, seats, None, _ ) => {
        reserveSeats( conferenceId = cid, orderId = oid, seats = seats, expiration = None )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case O.OrderPlaced( orderId, _, _, _, _ ) =>
        order( Some( orderId ) ) ! O.RejectOrder( targetId = orderId )
    }

    val awaitingReservationConfirmation: Receive = {
      case c: O.OrderUpdated => orderUpdated( c )

      case c: SA.SeatsReserved if workId == state.seatReservationWorkId => {
        val markAsReserved = O.MarkSeatsAsReserved(
          targetId = state.orderId,
          seats = c.reservationDetails.toSeq,
          expiration = state.reservationAutoExpiration
        )
        order( Some( state.orderId ) ) ! markAsReserved
        context.become(
          around( reservationConfirmationReceived orElse common orElse confirmedUnhandled )
        )
      }
    }

    val reservationConfirmationReceived: Receive = {
      case c: O.OrderUpdated   => orderUpdated( c )
      case c: O.OrderConfirmed => orderConfirmed( c )
      case _: P.PaymentCompleted => {
        order( Some( state.orderId ) ) ! O.ConfirmOrder( state.orderId )
        context.become(
          around( paymentConfirmationReceived orElse common orElse confirmedUnhandled )
        )
      }
    }

    val paymentConfirmationReceived: Receive = {
      case c: O.OrderConfirmed => orderConfirmed( c )
    }

    val confirmed: Receive = Actor.emptyBehavior

    val common: Receive = {
      case _: RS.ExpireRegistrationProcess if state.state != FullyConfirmed => {
        state = state.copy( state = OrderExpired )
        order( Some( state.orderId ) ) ! O.RejectOrder( state.orderId )
        val cancel = SA.CancelSeatReservation(
          targetId = state.conferenceId.as[SeatsAvailabilityState],
          reservationId = state.reservationId
        )

        seatsAvailability( Some( state.conferenceId.as[SeatsAvailabilityState] ) ) ! cancel
      }

      case sr: SA.SeatsReserved if workId != state.seatReservationWorkId => {
        log.warning(
          s"Seat reservation response for reservation id ${sr.reservationId} does not match the expected work id."
        )
      }
    }

    val confirmedUnhandled: Receive = {
      case c: SA.SeatsReserved => {
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
      val reservation = {
        SA.MakeSeatReservation(
          targetId = conferenceId.as[SeatsAvailabilityState],
          reservationId = orderId,
          seats = seats
        )
      }
      expiration foreach { exp =>
        val timeout = FiniteDuration( exp.getMillis - joda.DateTime.now.getMillis, MILLISECONDS )
        expirationMessager = context.system.scheduler.scheduleOnce( timeout ) {
          self !+ RS.ExpireRegistrationProcess( state.id )
        }
      }
      seatsAvailability( Some( conferenceId.as[SeatsAvailabilityState] ) ) ! reservation
    }

    def orderUpdated( e: O.OrderUpdated ): Unit = {
      val reservation = SA.MakeSeatReservation(
        targetId = state.conferenceId.as[SeatsAvailabilityState],
        reservationId = state.orderId,
        seats = e.seats
      )
//      state = state.copy( seatReservationWorkId = workId ) //DMR: isn't this cheating accept; how best to provide workId without closing on this in AR usage?
      seatsAvailability( Some( state.conferenceId.as[SeatsAvailabilityState] ) ) ! reservation
      accept( ( e, workId ) )
      context.become( around( awaitingReservationConfirmation orElse common ) )
    }

    def orderConfirmed( e: O.OrderConfirmed ): Unit = {
      val commit = SA.CommitSeatReservation(
        targetId = state.conferenceId.as[SeatsAvailabilityState],
        reservationId = state.reservationId
      )
      seatsAvailability( Some( state.conferenceId.as[SeatsAvailabilityState] ) ) ! commit
      context.become( around( confirmed orElse confirmedUnhandled ) )
    }

    override def unhandled( msg: Any ): Unit = ???
  }
}
