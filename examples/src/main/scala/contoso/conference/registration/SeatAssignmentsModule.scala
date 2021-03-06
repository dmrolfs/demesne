package contoso.conference.registration

import scala.annotation.tailrec
import akka.actor.Props
import akka.event.LoggingReceive
import contoso.conference.SeatType
import contoso.conference.registration.SeatAssignmentsProtocol.{ SeatAssignment, SeatAssignmentRef }
import contoso.registration.{ PersonalInfo, SeatQuantity }
import demesne._
import demesne.repository._
import omnibus.akka.publish.EventPublisher
import omnibus.identifier._

object SeatAssignmentsProtocol extends AggregateProtocol[SeatAssignmentsState, ShortUUID] {

  case class SeatAssignmentRef( id: SeatAssignmentsModule.TID, position: Int )
  case class SeatAssignment(
    seatTypeId: SeatType.TID,
    reference: Option[SeatAssignmentRef] = None,
    attendee: Option[PersonalInfo] = None
  )

  case class CreateSeatsAssignment(
    override val targetId: CreateSeatsAssignment#TID,
    orderId: OrderModule.TID,
    seats: Set[SeatQuantity]
  ) extends Command

  // Conference/Registration/Commands/AssignSeat.cs
  case class AssignSeat(
    override val targetId: AssignSeat#TID,
    seatTypeId: SeatType.TID,
    position: Int,
    attendee: PersonalInfo
  ) extends Command

  // Conference/Registration/Commands/UnassignSeat.cs
  case class UnassignSeat(
    override val targetId: UnassignSeat#TID,
    seatTypeId: SeatType.TID,
    position: Int
  ) extends Command

  // Registration.Contracts/Events/SeatAssigned.cs
  case class SeatAssigned(
    override val sourceId: SeatAssigned#TID,
    position: Int,
    seatTypeId: SeatType.TID,
    attendee: PersonalInfo
  ) extends Event

  // Registration.Contracts/Events/SeatAssignmentsCreated.cs
  case class SeatAssignmentsCreated(
    override val sourceId: SeatAssigned#TID,
    orderId: OrderModule.TID,
    seats: Seq[SeatAssignment]
  ) extends Event

  // Registration.Contracts/Events/SeatAssignmentsUpdated.cs
  case class SeatAssignmentsUpdated(
    override val sourceId: SeatAssignmentsUpdated#TID,
    position: Int,
    attendee: PersonalInfo
  ) extends Event

  // Registration.Contracts/Events/SeatUnassigned.cs
  case class SeatUnassigned( override val sourceId: SeatUnassigned#TID, position: Int )
      extends Event
}

case class SeatAssignmentsState(
  id: SeatAssignmentsState#TID,
  orderId: OrderModule.TID,
  seats: Seq[SeatAssignment]
) {
  type ID = SeatAssignmentsState.identifying.ID
  type TID = SeatAssignmentsState.identifying.TID
}

object SeatAssignmentsState {

  def updateSeats(
    state: SeatAssignmentsState
  )(
    id: SeatAssignmentsState#TID,
    position: Int,
    seatTypeId: Option[SeatType.TID],
    attendee: Option[PersonalInfo]
  ): SeatAssignmentsState = {
    if (state.seats.isDefinedAt( position )) {
      val effSeatType = seatTypeId getOrElse state.seats( position ).seatTypeId
      val assignment = SeatAssignment(
        seatTypeId = effSeatType,
        attendee = attendee,
        reference = Some( SeatAssignmentRef( id = id, position = position ) )
      )
      val newSeats = state.seats.take( position ) ++ (assignment +: state.seats.drop(
        position + 1
      ))
      state.copy( seats = newSeats )
    } else {
      state
    }
  }

  implicit val labeling = Labeling.custom[SeatAssignmentsState]( "SeatAssignments" )
  implicit val identifying = Identifying.byShortUuid[SeatAssignmentsState]
}

object SeatAssignmentsModule
    extends AggregateRootModule[SeatAssignmentsState, SeatAssignmentsState#ID] { module =>
  import SeatAssignmentsProtocol._
  import com.wix.accord._

  object Repository {
    def props( model: DomainModel ): Props = Props( new Repository( model ) )
  }

  class Repository( model: DomainModel )
      extends EnvelopingAggregateRootRepository( model, SeatAssignmentsType )
      with ClusteredAggregateContext {
    override def aggregateProps: Props = SeatAssignments.props( model, rootType )
  }

  object SeatAssignmentsType extends AggregateRootType {
    override def name: String = module.shardName
    override type S = SeatAssignmentsState
    override def repositoryProps( implicit model: DomainModel ): Props = Repository.props( model )
  }

  override val rootType: AggregateRootType = SeatAssignmentsType

  object SeatAssignments {

    def props( model: DomainModel, rootType: AggregateRootType ): Props = {
      Props( new SeatAssignments( model, rootType ) with EventPublisher )
    }
  }

  class SeatAssignments(
    override val model: DomainModel,
    override val rootType: AggregateRootType
  ) extends AggregateRoot[SeatAssignmentsState, SeatAssignmentsState#ID]
      with AggregateRoot.Provider { outer: EventPublisher =>

    override var state: SeatAssignmentsState = _

    override def acceptance: Acceptance = {
      case ( SeatAssignmentsCreated( id, orderId, seats ), _ ) => {
        SeatAssignmentsState( id.as[SeatAssignmentsState], orderId, seats )
      }

      case ( SeatAssigned( id, position, seatTypeId, attendee ), s ) => {
        SeatAssignmentsState.updateSeats( s )(
          id,
          position,
          Some( seatTypeId ),
          Some( attendee )
        )
      }

      case ( SeatAssignmentsUpdated( id, position, attendee ), s ) => {
        SeatAssignmentsState.updateSeats( s )( id, position, None, Some( attendee ) )
      }

      case ( SeatUnassigned( id, position ), s ) => {
        SeatAssignmentsState.updateSeats( s )( id, position, None, None )
      }
    }

    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = LoggingReceive {
      case CreateSeatsAssignment( id, orderId, seats ) => {
        def makeAssignments( seats: Set[SeatQuantity] ): Seq[SeatAssignment] = {
          def fromSpec( spec: SeatQuantity ): Seq[SeatAssignment] = {
            for (i <- 0 until spec.quantity.value.toInt) yield {
              SeatAssignment(
                seatTypeId = spec.seatTypeId,
                reference = Some( SeatAssignmentRef( id = state.id, position = i ) )
              )
            }
          }

          @tailrec
          def loop(
            seats: List[SeatQuantity],
            pos: Int,
            acc: Seq[SeatAssignment]
          ): Seq[SeatAssignment] = seats match {
            case Nil    => acc
            case h :: t => loop( t, (pos + h.quantity.value.toInt), acc ++ fromSpec( h ) )
          }

          loop( seats.toList, 0, Seq() )
        }

        val assignments = makeAssignments( seats )
        persist( SeatAssignmentsCreated( id, orderId, assignments ) ) { event =>
          acceptAndPublish( event )
          context.become( around( active orElse unhandled ) )
        }
      }
    }

    val active: Receive = LoggingReceive {
      case AssignSeat( _, _, position, attendee )
          if (state.seats.isDefinedAt( position ) && (validate( attendee ) == Success)) => {
        val current = state.seats( position )
        val events = makeAssignmentEvents( current, attendee, position )
        events foreach { e =>
          persist( e ) { acceptAndPublish }
        }
      }

      case UnassignSeat( id, _, position )
          if (state.seats.isDefinedAt( position ) && state
            .seats( position )
            .attendee
            .isDefined) => {
        persist( SeatUnassigned( id, position ) ) { acceptAndPublish }
      }
    }

    def makeAssignmentEvents(
      assignment: SeatAssignment,
      attendee: PersonalInfo,
      pos: Int
    ): Seq[SeatAssignmentsProtocol.Event] = {
      assignment.attendee map { currentAttendee =>
        val result = scala.collection.mutable.Seq()
        if (!attendee.email.equalsIgnoreCase( currentAttendee.email )) {
          result :+ SeatUnassigned( state.id, pos )
          result :+ SeatAssigned(
            sourceId = state.id,
            position = pos,
            seatTypeId = assignment.seatTypeId,
            attendee = attendee
          )
        } else if (!attendee.firstName.equalsIgnoreCase( currentAttendee.firstName ) ||
                   !attendee.lastName.equalsIgnoreCase( currentAttendee.lastName )) {
          result :+ SeatAssignmentsUpdated(
            sourceId = state.id,
            position = pos,
            attendee = attendee
          )
        }
        result.toSeq
      } getOrElse {
        Seq(
          SeatAssigned(
            sourceId = state.id,
            position = pos,
            seatTypeId = assignment.seatTypeId,
            attendee = attendee
          )
        )
      }
    }

    val unhandled: Receive = omnibus.core.emptyBehavior[Any, Unit]
  }
}
