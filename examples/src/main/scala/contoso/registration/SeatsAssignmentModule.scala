// package contoso.registration


// trait SeatsAssignmentModule extends AggregateRootModule {
//   import SeatsAssignmentModule.trace

//   abstract override def start( ctx: Map[Symbol, Any] ): Unit = trace.block( "start" ) {
//     super.start( ctx )

//     SeatsAssignmentModule.initialize( ctx )
//     val model = ctx( 'model ).asInstanceOf[DomainModel]
//     implicit val system = SeatsAssignmentModule.system
//     val rootType = SeatsAssignmentModule.aggregateRootType
//     startClusterShard( rootType )
//     model.registerAggregateType( rootType, ClusteredActorFactory )
//   }
// }

// object SeatsAssignmentModule extends AgrgegateRootModuleCompanion { module =>
//   val trace = Trace[SeatsAssignmentModule.type]
  
//   override val aggregateIdTag: Symbol = 'seatsAssignment

//   override def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType = {
//     new AggregateRootType {
//       override val name: String = module.shardName
//       override def aggregateRootProps: Props = SeatsAssignment.props( this )
//       override val toString: String = "SeatsAssignmentAggregateRootType"
//     }
//   }


//   case class SeatsAssignmentRef( seatsAssignmentId: module.TID, position: Int )
//   case class SeatAssignment( seatTypeId: SeatType.TID, attendee: Option[PersonalInfo], reference: Option[SeatsAssignmentRef] )


//   sealed trait Command extends CommandLike {
//     override type ID = module.ID
//   }

//   case class CreateSeatsAssignment( 
//     override val targetId: CreateSeatsAssignment#TID, 
//     orderId: OrderModule.TID, 
//     seats: Set[SeatQuantity] 
//   ) extends Command

//   // Conference/Registration/Commands/AssignSeat.cs
//   case class AssignSeat( 
//     override val targetId: AssignSeat#TID, 
//     seatTypeId: SeatType.TID, 
//     position: Int, 
//     attendee: PersonalInfo 
//   ) extends Command

//   // Conference/Registration/Commands/UnassignSeat.cs
//   case class UnassignSeat( override val targetId: UnassignSeat#TID, seatTypeId: SeatType.TID, position: Int ) extends Command

  
//   sealed trait Event extends EventLike {
//     override type ID = module.ID
//   }

//   // Registration.Contracts/Events/SeatAssigned.cs
//   case class SeatAssigned( 
//     override val sourceId: SeatAssigned#TID, 
//     position: Int, 
//     seatTypeId: SeatType.TID, 
//     attendee: PersonalInfo 
//   )

//   // Registration.Contracts/Events/SeatAssignmentsCreated.cs
//   case class SeatAssignmentsCreated( 
//     override val sourceId: SeatAssigned#TID, 
//     orderId: OrderModule#TID, 
//     seats: Seq[SeatAssignment] 
//   )

//   // Registration.Contracts/Events/SeatAssignmentsUpdated.cs
//   case class SeatAssignmentsUpdated( override val sourceId: SeatAssignmentsUpdated#TID, position: Int, attendee: PersonalInfo )

//   // Registration.Contracts/Events/SeatUnassigned.cs
//   case class SeatUnassigned( override val sourceId: SeatUnassigned#TID, position: Int )


//   case class SeatsAssignmentState( id: TID, orderId: OrderModule.TID, seats: Seq[SeatAssignment] )

//   object SeatsAssignmentState {
//     implicit val stateSpec = new AggregateStateSpecification[SeatsAssignmentState] {
//       override def acceptance( state: SeatsAssignmentState ): PartialFunction[Any, SeatsAssignmentState] = {
//         case SeatAssignmentsCreated( id, orderId, seats ) => SeatsAssignmentState( id = id, orderId = orderId, seats = seats )

//         case SeatAssigned( id, position, seatTypeId, attendee ) => updateSeats( id, position, Some(seatTypeId), Some(attendee) )

//         case SeatAssignmentsUpdated( id, position, attendee ) => updateSeats( id, position, None, Some(attendee) )

//         case SeatUnassigned( id, position ) => updateSeats( id, position, None, None )
//       }

//       private def updateSeats( 
//         id: module.TID,
//         position: Int, 
//         seatTypeId: Option[SeatType.TID], 
//         attendee: Option[PersonalInfo] 
//       ): Seq[SeatAssignment] = {
//         if ( state.seats.isDefinedAt( position ) ) {
//           val effSeatType = seatTypeId getOrElse state.seats( position ).seatTypeId
//           val assignment = SeatAssignment( 
//             seatTypeId = effSeatType, 
//             attendee = Some( attendee ), 
//             reference = Some( 
//               SeatsAssignmentRef( 
//                 seatsAssignmentId = id, 
//                 orderId = state.orderId, 
//                 position = position 
//               ) 
//             ) 
//           )
//           val newSeats = state.seats.take( position ) ++ ( assignment +: state.seats.drop( position + 1 ) )
//           state.copy( seats = newSeats )
//         } else {
//           state
//         }
//       }
//     }
//   }
// }


// object SeatsAssignment {
//   def props( meta: AggregateRootType ): Props = Props( new SeatsAssignment with LocalPublisher )
// }

// class SeatsAssignment( override val meta: AggregateRootType ) extends AggregateRoot[SeatsAssignmentState] {
//   outer: EventPublisher =>

//   import SeatsAssignment._

//   override val trace = Trace( "SeatsAssignment", log )

//   override protected var state: SeatsAssignmentState = _

//   override def transisitionFor( state: SeatsAssignmentState ): Transition = {
//     case _: SeatAssignmentsCreated => context.become( active orElse unhandled )
//     // case _: SeatAssigned => context.become( active orElse unhandled )
//     // case _: SeatAssignmentsUpdated => context.become( active orElse unhandled )
//     // case _: SeatUnassigned => context.become( active orElse unhandled )
//   }

//   override def pathname: String = self.path.name

//   override val quiescent: Recieve = LoggingReceive {
//     case c @ CreateSeatsAssignment( id, orderId, seats ) => 
//   }

//   val active: Recieve = peds.commons.util.emptyBehavior[Any, Unit]

//   val unhandled: Receive = peds.commons.util.emptyBehavior[Any, Unit]
// }

// //           SeatsAssignmentState( id = id, orderId = orderId, seats = Map( seats zipWithIndex ):_* )
// // quiscient: OrderModule.OrderConfirmed => SeatAssignmentsCreated