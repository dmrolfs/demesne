package contoso.conference.registration

import scala.concurrent.duration._
import scala.reflect._
import akka.actor._

import scalaz.Scalaz._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time.{Imports => joda}
import contoso.conference.ConferenceModule
import contoso.conference.payments.PaymentSourceModule
import contoso.registration.SeatQuantity
import demesne._
import demesne.repository.AggregateRootRepository.ClusteredAggregateContext
import demesne.repository.EnvelopingAggregateRootRepository
import peds.akka.envelope._
import peds.akka.publish.EventPublisher
import peds.commons.TryV
import peds.commons.identifier._
import peds.commons.log.Trace


object RegistrationSagaProtocol extends AggregateProtocol[ShortUUID] {
  case class ExpireRegistrationProcess( override val targetId: ExpireRegistrationProcess#TID ) extends CommandMessage
  case class RegistrationProcessExpired( override val sourceId: RegistrationProcessExpired#TID ) extends EventMessage
}

/**
* Represents a Saga that is in charge of communicating between the different distributed components when registering
* to a conference, reserving the seats, expiring the reservation in case the order is not completed in time, etc.
*
* Created by damonrolfs on 9/11/14.
*/
object RegistrationSagaModule extends SagaModule { module =>
  val trace = Trace[RegistrationSagaModule.type]

  override type ID = ShortUUID

  override def nextId: TryV[TID] = implicitly[Identifying[RegistrationSagaState]].nextIdAs[TID]


  object Repository {
    def props( model: DomainModel ): Props = Props( new Repository( model ) )
  }

  class Repository( model: DomainModel )
  extends EnvelopingAggregateRootRepository( model, rootType ) with ClusteredAggregateContext {
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
    override lazy val identifying: Identifying[_] = registrationSagaIdentifying
    override def repositoryProps( implicit model: DomainModel ): Props = Repository.props( model )
  }

  override val rootType: AggregateRootType = RegistrationSagaType


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


  implicit val registrationSagaIdentifying: Identifying[RegistrationSagaState] = {
    new Identifying[RegistrationSagaState] with ShortUUID.ShortUuidIdentifying[RegistrationSagaState] {
      override val idTag: Symbol = 'registration
      override def idOf( o: RegistrationSagaState ): TID = o.id
    }
  }


  object RegistrationSaga {
    def props(
      rootType: AggregateRootType,
      model: DomainModel,
      orderType: AggregateRootType,
      availabilityType: AggregateRootType
    ): Props = {
      Props( new RegistrationSaga( rootType, model, orderType, availabilityType ) with EventPublisher )
    }

    //DMR: det where to locate this b/h; e.g., pull-req into nscala-time, peds?
    implicit def period2FiniteDuration( p: joda.Period ): FiniteDuration = FiniteDuration( p.getMillis, MILLISECONDS )
  }

  class RegistrationSaga(
    override val rootType: AggregateRootType,
    override val model: DomainModel,
    orderType: AggregateRootType,
    seatsAvailabilityType: AggregateRootType
  ) extends Saga[RegistrationSagaState, ShortUUID] { outer: EventPublisher =>
    import contoso.conference.registration.{ RegistrationSagaProtocol => RS }
    import contoso.conference.registration.{ OrderProtocol => O }
    import contoso.conference.registration.{ SeatsAvailabilityProtocol => SA }
    import contoso.conference.payments.{ PaymentSourceProtocol => P }

    override val trace = Trace( "RegistrationSaga", log )

    override def parseId( idstr: String ): TID = {
      val identifying = implicitly[Identifying[RegistrationSagaState]]
      identifying.safeParseId[ID]( idstr )( classTag[ShortUUID] )
    }

    override var state: RegistrationSagaState = _

    import context.dispatcher
    var expirationMessager: Cancellable = _

    override def acceptance: Acceptance = {
      case ( O.OrderPlaced(id, cid, seats, expiration, accessCode), state ) => {
        state.copy(
          conferenceId = cid,
          orderId = id,
          reservationId = id, // Use the order id as an opaque reservation id for the seat reservation. It could be anything else, as long as it is deterministic from the OrderPlaced event.
          state = AwaitingReservationConfirmation,
          reservationAutoExpiration = expiration,
          lastUpdated = joda.DateTime.now
        )
      }

      case ( (_: O.OrderUpdated, workId: WorkId), state ) => {
        state.copy( state = AwaitingReservationConfirmation, seatReservationWorkId = workId )
      }

      case ( _: SA.SeatsReserved, state ) => state.copy( state = ReservationConfirmationReceived )
      case ( _: P.PaymentCompleted, state ) => state.copy( state = PaymentConfirmationReceived )
      case ( _: O.OrderConfirmed, state ) => state.copy( state = FullyConfirmed )
    }

    def order( id: Option[OrderModule.TID] ): ActorRef = {
      OrderModule.aggregateOf( id getOrElse ShortUUID() )( model )
    }

    def seatsAvailability( id: Option[SeatsAvailabilityModule.TID] ): ActorRef = {
      SeatsAvailabilityModule.aggregateOf( id getOrElse ShortUUID() )( model )
    }


    override def receiveCommand: Receive = around( notStarted orElse common )

    val notStarted: Receive = {
      case e @ O.OrderPlaced(oid, cid, seats, Some(expiration), accessCode) if expiration > joda.DateTime.now => {
        reserveSeats( conferenceId = cid, orderId = oid, seats = seats, expiration = Some(expiration) )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case e @ O.OrderPlaced(oid, cid, seats, None, accessCode) => {
        reserveSeats( conferenceId = cid, orderId = oid, seats = seats, expiration = None )
        accept( e )
        context.become( around( awaitingReservationConfirmation orElse common ) )
      }

      case O.OrderPlaced( orderId, _, _, _, _ ) => order( Some(orderId) ) ! O.RejectOrder( targetId = orderId )
    }

    val awaitingReservationConfirmation: Receive = {
      case c: O.OrderUpdated => orderUpdated( c )

      case c @ SA.SeatsReserved(id, rid, details, availableSeatsChanged) if workId == state.seatReservationWorkId => {
        val markAsReserved = O.MarkSeatsAsReserved(
          targetId = state.orderId,
          seats = details.toSeq,
          expiration = state.reservationAutoExpiration
        )
        order( Some(state.orderId) ) ! markAsReserved
        context.become( around( reservationConfirmationReceived orElse common orElse confirmedUnhandled ) )
      }
    }

    val reservationConfirmationReceived: Receive = {
      case c: O.OrderUpdated => orderUpdated( c )
      case c: O.OrderConfirmed => orderConfirmed( c )
      case c @ P.PaymentCompleted( sourceId: P.PaymentCompleted#TID, paymentSourceId: PaymentSourceModule.TID ) => {
        order( Some(state.orderId) ) ! O.ConfirmOrder( state.orderId )
        context.become( around( paymentConfirmationReceived orElse common orElse confirmedUnhandled ) )
      }
    }

    val paymentConfirmationReceived: Receive = {
      case c: O.OrderConfirmed => orderConfirmed( c )
    }

    val confirmed: Receive = Actor.emptyBehavior

    val common: Receive = {
      case _: RS.ExpireRegistrationProcess if state.state != FullyConfirmed => {
        state = state.copy( state = OrderExpired )
        order( Some(state.orderId) ) ! O.RejectOrder( state.orderId )
        val cancel = SA.CancelSeatReservation( targetId = state.conferenceId, reservationId = state.reservationId )
        seatsAvailability( Some(state.conferenceId) ) ! cancel
      }

      case c @ SA.SeatsReserved(id, rid, details, availableSeatsChanged) if workId != state.seatReservationWorkId => {
        log warning s"Seat reservation response for reservation id ${rid} does not match the expected work id."
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
      val reservation = SA.MakeSeatReservation( targetId = conferenceId, reservationId = orderId, seats = seats )
      expiration foreach { exp =>
        val timeout = FiniteDuration( exp.getMillis - joda.DateTime.now.getMillis, MILLISECONDS )
        expirationMessager = context.system.scheduler.scheduleOnce( timeout ) {
          self !+ RS.ExpireRegistrationProcess( state.id )
        }
      }
      seatsAvailability( Some(conferenceId) ) ! reservation
    }

    def orderUpdated( e: O.OrderUpdated ): Unit = {
      val reservation = SA.MakeSeatReservation(
        targetId = state.conferenceId,
        reservationId = state.orderId,
        seats = e.seats
      )
//      state = state.copy( seatReservationWorkId = workId ) //DMR: isn't this cheating accept; how best to provide workId without closing on this in AR usage?
      seatsAvailability( Some(state.conferenceId) ) ! reservation
      accept( (e, workId) )
      context.become( around( awaitingReservationConfirmation orElse common ) )
    }

    def orderConfirmed( e: O.OrderConfirmed ): Unit = {
      val commit = SA.CommitSeatReservation( targetId = state.conferenceId, reservationId = state.reservationId )
      seatsAvailability( Some(state.conferenceId) ) ! commit
      context.become( around( confirmed orElse confirmedUnhandled ))
    }

    override def unhandled( msg: Any ): Unit = ???
  }
}
