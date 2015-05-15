package contoso.conference.registration

import akka.actor.Props
import akka.event.LoggingReceive
import contoso.conference.{ConferenceModule, SeatType}
import contoso.registration.SeatQuantity
import demesne._
import demesne.register.RegisterBus
import peds.akka.publish.EventPublisher
import peds.commons.log.Trace
import squants.{Dimensionless, Each}


/**
 * Manages the availability of conference seats. Currently there is one SeatsAvailability instance per conference.
 *
 * Some of the instances of SeatsAvailability are highly contentious, as there could be several users trying to register
 * for the same conference at the same time.
 */
object SeatsAvailabilityModule extends AggregateRootModule{ module =>
  val trace = Trace[SeatsAvailabilityModule.type]

  override type ID = ConferenceModule.ID // SeatsAvailability supports corresponding Conference

  // override val aggregateIdTag: Symbol = 'seatsAvailability

  override val aggregateRootType: AggregateRootType = {
    new AggregateRootType {// def actorFactory: ActorFactory
      override def name: String = module.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = SeatsAvailability.props( model, this )
      // override  val toString: String = shardName + "AggregateRootType"
    }
  }


  // targetId is conference ID
  sealed trait Command extends CommandLike {
    override type ID = module.ID
    def conferenceId: ConferenceModule.TID = targetId
  }

  // Conference/Registration/Commands/MakeSeatReservation.cs
  case class MakeSeatReservation(
    override val targetId: MakeSeatReservation#TID,
    reservationId: OrderModule.TID,
    seats: Seq[SeatQuantity]
  ) extends Command

  // Conference/Registration/Commands/CancelSeatReservation.cs
  case class CancelSeatReservation(
    override val targetId: CancelSeatReservation#TID,
    reservationId: OrderModule.TID
  ) extends Command

  // Conference/Registration/Commands/CommitSeatReservation.cs
  case class CommitSeatReservation(
    override val targetId: CommitSeatReservation#TID,
    reservationId: OrderModule.TID
  ) extends Command

  // Conference/Registration/Commands/AddSeats.cs
  case class AddSeats(
    override val targetId: AddSeats#TID,
    seatTypeId: SeatType.TID,
    quantity: Dimensionless
  ) extends Command

  // Conference/Registration/Commands/RemoveSeats.cs
  case class RemoveSeats(
    override val targetId: RemoveSeats#TID,
    seatTypeId: SeatType.TID,
    quantity: Dimensionless
  ) extends Command


  sealed trait Event extends EventLike {
    override type ID = module.ID
    // override val sourceTypeName: Option[String] = Option( module.aggregateRootType.name )
  }

  // Conference/Registration/Events/AvailableSeatsChanged.cs
  case class AvailableSeatsChanged(
    override val sourceId: AvailableSeatsChanged#TID,
    seats: Set[SeatQuantity] // DMR: orig is Enumerable / Iterable; Set okay or keep Seq?
  ) extends Event

  // Conference/Registration/Events/SeatsReservationCancelled.cs
  case class SeatsReservationCancelled(
    override val sourceId: SeatsReservationCancelled#TID,
    reservationId: OrderModule.TID,
    availableSeatsChanged: Set[SeatQuantity]
  ) extends Event

  // Conference/Registration/Events/SeatsReservationCommitted.cs
  case class SeatsReservationCommitted(
    override val sourceId: SeatsReservationCommitted#TID,
    reservationId: OrderModule.TID
  ) extends Event

  // Conference/Registration/Events/SeatsReserved.cs
  case class SeatsReserved(
    override val sourceId: SeatsReserved#TID,
    reservationId: OrderModule.TID,
    reservationDetails: Set[SeatQuantity],
    availableSeatsChanged: Set[SeatQuantity]
  ) extends Event


  // Conference/Registration/SeatsAvailability.cs
  case class SeatsAvailabilityState(
    id: TID,
    remainingSeats: SeatsAvailabilityState.SeatTypesRemaining = Map(),
    pendingReservations: SeatsAvailabilityState.PendingReservations = Map()
  )


  object SeatsAvailabilityState {
    type SeatTypesRemaining = Map[SeatType.TID, Dimensionless]
    type PendingReservations = Map[OrderModule.TID, Seq[SeatQuantity]]

    implicit val stateSpec = new AggregateStateSpecification[SeatsAvailabilityState] {
      override def acceptance( state: SeatsAvailabilityState ): Acceptance = {
        // Conference/Registration/SeatsAvailability.cs[185-198]
        case AvailableSeatsChanged( _, seats ) => {
          val updated = addToRemainingSeats( state.remainingSeats, seats )
          state.copy( remainingSeats = updated )
        }

        // Conference/Registration/SeatsAvailability.cs[223-231]
        case SeatsReservationCancelled( _, reservationId, availableSeatsChanged ) => {
          val updatedPending = state.pendingReservations - reservationId
          val updatedRemaining = addToRemainingSeats( state.remainingSeats, availableSeatsChanged )
          state.copy( pendingReservations = updatedPending, remainingSeats = updatedRemaining )
        }

        // Conference/Registration/SeatsAvailability.cs[218 - 221]
        case SeatsReservationCommitted( _, reservationId ) => {
          state.copy( pendingReservations = (state.pendingReservations - reservationId) )
        }

        // Conference/Registration/SeatsAvailability.cs[200-216]
        case SeatsReserved( _, reservationId, reservationDetails, availableSeatsChanged ) => {
          val updatedPending = (
            if ( reservationDetails.size > 0 ) state.pendingReservations + (reservationId -> reservationDetails.toSeq)
            else state.pendingReservations - reservationId
          )
          val updatedRemaining = addToRemainingSeats( state.remainingSeats, availableSeatsChanged )
          state.copy( pendingReservations = updatedPending, remainingSeats = updatedRemaining )
        }
      }
    }

    def addToRemainingSeats( original: SeatTypesRemaining, newAvailable: Set[SeatQuantity] ): SeatTypesRemaining = {
      val newRemainingSeats = for {
        avail <- newAvailable.toSeq
        o <- original.get( avail.seatTypeId )
      } yield {
        val updatedSeats = o + avail.quantity
        (avail.seatTypeId -> updatedSeats)
      }
      original ++ newRemainingSeats
    }
  }


  object SeatsAvailability {
    def props( model: DomainModel, meta: AggregateRootType ): Props = {
      Props( new SeatsAvailability( model, meta ) with EventPublisher )
    }
  }

  class SeatsAvailability(
    model: DomainModel,
    override val meta: AggregateRootType
  ) extends AggregateRoot[SeatsAvailabilityState] { outer: EventPublisher =>
    override val trace = Trace( "SeatsAvailability", log )

    override val registerBus: RegisterBus = model.registerBus

    override var state: SeatsAvailabilityState = _

    override def receiveCommand: Receive = around {
      LoggingReceive {
        // Conference/Registration/Handlers/SeatsAvailabilityHandler.cs [60-68]
        // Conference/Registration/SeatsAvailability.cs [85-88]
        case AddSeats( _, seatTypeId, quantity ) => {
          persist( makeAvailableSeatsChangedEvent(seatTypeId, quantity) ) { event => state = acceptAndPublish( event ) }
        }

        // Conference/Registration/Handlers/SeatsAvailabilityHandler.cs [70-78]
        // Conference/Registration/SeatsAvailability.cs [90-93]
        case RemoveSeats( conferenceId, seatTypeId, quantity ) => {
          persist( makeAvailableSeatsChangedEvent(seatTypeId, -1 * quantity) ) { e => state = acceptAndPublish( e ) }
        }

        // Conference/Registration/Handlers/SeatsAvailabilityHandler.cs [37-42]
        // Conference/Registration/SeatsAvailability.cs [95-135]
        case c @ MakeSeatReservation( conferenceId, reservationId, seats )
        if seats forall { s => state.remainingSeats.contains( s.seatTypeId ) } => {
          persist( makeSeatsReservedEvent( reservationId, calculateDifference( reservationId, seats ) ) ) { event =>
            state = acceptAndPublish( event )
          }
        }

        // Conference/Registration/Handlers/SeatsAvailabilityHandler.cs [44-49]
        // Conference/Registration/SeatsAvailability.cs [137-148]
        case CancelSeatReservation( conferenceId, reservationId ) => {
          val reservation = state.pendingReservations.getOrElse( reservationId, Seq() )
          persist( SeatsReservationCancelled( conferenceId, reservationId, reservation.toSet ) ) { event =>
            state = acceptAndPublish( event )
          }
        }

        // Conference/Registration/Handlers/SeatsAvailabilityHandler.cs [51-56]
        // Conference/Registration/SeatsAvailability.cs [150-156]
        case CommitSeatReservation( conferenceId, reservationId ) => {
          persist( SeatsReservationCommitted( conferenceId, reservationId ) ) { e => state = acceptAndPublish( e ) }
        }
      }
    }

    def makeAvailableSeatsChangedEvent( seatTypeId: SeatType.TID, quantity: Dimensionless ): AvailableSeatsChanged = {
      AvailableSeatsChanged(
        sourceId = state.id,
        seats = Set( SeatQuantity( seatTypeId = seatTypeId, quantity = quantity ) )
      )
    }

    // Conference/Registration/SeatsAvailability.cs [158-171]
    case class SeatDifference( wanted: Dimensionless, existing: Dimensionless, remaining: Dimensionless ) {
      def actual: Dimensionless = Seq( wanted, Seq( remaining, Each(0) ).max + existing).min
      def deltaSinceLast: Dimensionless = actual - existing
    }

    type SeatTypeDifferences = Map[SeatType.TID, SeatDifference]

    // Conference/Registration/SeatsAvailability.cs [109-125]
    def calculateDifference( reservationId: OrderModule.TID, wanted: Seq[SeatQuantity] ): SeatTypeDifferences = {
      val existing = {
        val e = for {
          sq <- state.pendingReservations.getOrElse( reservationId, Seq() )
        } yield SeatQuantity.unapply( sq )

        Map( e.flatten:_* )
      }

      val result = for {
        w <- wanted
        stid = w.seatTypeId
        remaining = state.remainingSeats.getOrElse( stid, Each(0) )
        e = existing.getOrElse( stid, Each(0) )
      } yield {
        val diff = SeatDifference(
          wanted = w.quantity,
          existing = e,
          remaining = remaining
        )
        (stid -> diff)
      }

      Map( result:_* )
    }

    // Conference/Registration/SeatsAvailability.cs [127-132]
    def makeSeatsReservedEvent( reservationId: OrderModule.TID, differences: SeatTypeDifferences ): SeatsReserved = {
      val details: Iterable[SeatQuantity] = for {
        diff <- differences
        actual = diff._2.actual
        if actual != 0
        stid = diff._1
      } yield SeatQuantity( seatTypeId = stid, quantity = actual )

      val changed: Iterable[SeatQuantity] = for {
        diff <- differences
        c = diff._2.deltaSinceLast
        if c != 0
        stid = diff._1
      } yield SeatQuantity( seatTypeId = stid, quantity = c )

      SeatsReserved(
        sourceId = state.id,
        reservationId = reservationId,
        reservationDetails = details.toSet,
        availableSeatsChanged = changed.toSet
      )
    }
  }
}



