package contoso.conference.registration

import scala.annotation.tailrec
import akka.actor.{ ActorSystem, Props }
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.akka.publish.{ EventPublisher, LocalPublisher }
import demesne._
import contoso.conference.SeatType
import contoso.registration.{SeatQuantity, PersonalInfo}


trait SeatAssignmentsModule extends AggregateRootModule {
   import SeatAssignmentsModule.trace

   abstract override def start( ctx: Map[Symbol, Any] ): Unit = trace.block( "start" ) {
     super.start( ctx )

     SeatAssignmentsModule.initialize( ctx )
     val model = SeatAssignmentsModule.model
     implicit val system = SeatAssignmentsModule.system
     val rootType = SeatAssignmentsModule.aggregateRootType
     model.registerAggregateType( rootType, demesne.factory.clusteredFactory )
   }
}

object SeatAssignmentsModule extends AggregateRootModuleCompanion { module =>
  import com.wix.accord._

  val trace = Trace[SeatAssignmentsModule.type]

  override val aggregateIdTag: Symbol = 'seatsAssignment

  override def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName
      override def aggregateRootProps: Props = SeatAssignments.props( this )
      override val toString: String = shardName + "AggregateRootType"
    }
  }


  case class SeatAssignmentRef( id: module.TID, position: Int )
  case class SeatAssignment(
    seatTypeId: SeatType.TID,
    reference: Option[SeatAssignmentRef] = None,
    attendee: Option[PersonalInfo] = None
  )


  sealed trait Command extends CommandLike {
    override type ID = module.ID
  }

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


  sealed trait Event extends EventLike {
    override type ID = module.ID
    override val sourceTypeName: Option[String] = Option( module.aggregateRootType.name )
  }

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
  case class SeatUnassigned( override val sourceId: SeatUnassigned#TID, position: Int ) extends Event


  case class SeatAssignmentsState( id: TID, orderId: OrderModule.TID, seats: Seq[SeatAssignment] )

  object SeatAssignmentsState {
    implicit val stateSpec = new AggregateStateSpecification[SeatAssignmentsState] {
      override def acceptance( state: SeatAssignmentsState ): Acceptance = {
        case SeatAssignmentsCreated( id, orderId, seats ) => SeatAssignmentsState( id = id, orderId = orderId, seats = seats )

        case SeatAssigned( id, position, seatTypeId, attendee ) => {
          updateSeats( state )( id, position, Some(seatTypeId), Some(attendee) )
        }

        case SeatAssignmentsUpdated( id, position, attendee ) => updateSeats( state )( id, position, None, Some(attendee) )

        case SeatUnassigned( id, position ) => updateSeats( state )( id, position, None, None )
      }

      private def updateSeats(
        state: SeatAssignmentsState
      )(
        id: module.TID,
        position: Int,
        seatTypeId: Option[SeatType.TID],
        attendee: Option[PersonalInfo]
      ): SeatAssignmentsState = {
        if ( state.seats.isDefinedAt( position ) ) {
          val effSeatType = seatTypeId getOrElse state.seats( position ).seatTypeId
          val assignment = SeatAssignment(
            seatTypeId = effSeatType,
            attendee = attendee,
            reference = Some( SeatAssignmentRef( id = id, position = position ) )
          )
          val newSeats = state.seats.take( position ) ++ ( assignment +: state.seats.drop( position + 1 ) )
          state.copy( seats = newSeats )
        } else {
          state
        }
      }
    }
  }


  object SeatAssignments {
    def props( meta: AggregateRootType ): Props = Props( new SeatAssignments( meta ) with LocalPublisher )
  }

  class SeatAssignments( override val meta: AggregateRootType ) extends AggregateRoot[SeatAssignmentsState] {
    outer: EventPublisher =>

    override val trace = Trace( "SeatsAssignment", log )

    override var state: SeatAssignmentsState = _

    override def transitionFor( state: SeatAssignmentsState ): Transition = {
      case _: SeatAssignmentsCreated => context.become( around( active orElse unhandled ) )
      // case _: SeatAssigned => context.become( active orElse unhandled )
      // case _: SeatAssignmentsUpdated => context.become( active orElse unhandled )
      // case _: SeatUnassigned => context.become( active orElse unhandled )
    }

    //   override def pathname: String = self.path.name

    case class SeatAssignmentsCreated(
      override val sourceId: SeatAssigned#TID,
      orderId: OrderModule.TID,
      seats: Seq[SeatAssignment]
    ) extends Event

    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = LoggingReceive {
      case c @ CreateSeatsAssignment( id, orderId, seats ) => {
        def makeAssignments( seats: Set[SeatQuantity] ): Seq[SeatAssignment ]= {
          def fromSpec( spec: SeatQuantity ): Seq[SeatAssignment] = {
            for ( i <- 0 until spec.quantity.value.toInt ) yield {
              SeatAssignment(
                seatTypeId = spec.seatTypeId,
                reference = Some( SeatAssignmentRef( id = state.id, position = i ) )
              )
            }
          }

          @tailrec
          def loop( seats: List[SeatQuantity], pos: Int, acc: Seq[SeatAssignment] ): Seq[SeatAssignment] = seats match {
            case Nil => acc
            case h :: t => loop( t, (pos + h.quantity.value.toInt), acc ++ fromSpec( h ) )
          }

          loop( seats.toList, 0, Seq() )
        }

        val assignments = makeAssignments( seats )
        persist( SeatAssignmentsCreated( id, orderId, assignments) ) { event => state = acceptAndPublish( event ) }
      }
    }

    val active: Receive = LoggingReceive {
      case AssignSeat( id, seatTypeId, position, attendee )
      if ( state.seats.isDefinedAt( position ) && (validate(attendee) == Success) ) => {
        val current = state.seats( position )
        val events = makeAssignmentEvents( current, attendee, position )
        events foreach { e => persist( e ) { event => state = acceptAndPublish( event ) } }
      }

      case UnassignSeat( id, seatTypeId, position )
      if ( state.seats.isDefinedAt( position ) && state.seats(position).attendee.isDefined ) => {
        persist( SeatUnassigned( id, position ) ) { event => state = acceptAndPublish( event ) }
      }
    }

    def makeAssignmentEvents( assignment: SeatAssignment, attendee: PersonalInfo, pos: Int ): Seq[Event] = {
      assignment.attendee map { currentAttendee =>
        val result = scala.collection.mutable.Seq()
        if ( !attendee.email.equalsIgnoreCase( currentAttendee.email ) ) {
          result :+ SeatUnassigned( state.id, pos )
          result :+ SeatAssigned(
            sourceId = state.id,
            position = pos,
            seatTypeId = assignment.seatTypeId,
            attendee = attendee
          )
        } else if ( !attendee.firstName.equalsIgnoreCase( currentAttendee.firstName ) ||
                    !attendee.lastName.equalsIgnoreCase( currentAttendee.lastName) ) {
          result :+ SeatAssignmentsUpdated( sourceId = state.id, position = pos, attendee = attendee )
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

    val unhandled: Receive = peds.commons.util.emptyBehavior[Any, Unit]
  }
}



//           SeatsAssignmentState( id = id, orderId = orderId, seats = Map( seats zipWithIndex ):_* )
// quiscient: OrderModule.OrderConfirmed => SeatAssignmentsCreated