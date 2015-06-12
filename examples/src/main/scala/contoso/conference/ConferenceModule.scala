package contoso.conference

import scala.concurrent.Future
import scalaz._, Scalaz._
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import com.github.nscala_time.time.{Imports => joda}
import demesne._
import demesne.register.RegisterBus
import peds.commons.V
import peds.akka.AskRetry._
import peds.akka.publish._
import peds.commons.log.Trace
import shapeless._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object ConferenceModule extends AggregateRootModule { module =>
  //DMR move these into common AggregateModuleCompanion trait
  val trace = Trace[ConferenceModule.type]

  var conferenceContext: ActorRef = _

  override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : V[Future[Unit]] = {
    checkConferenceContext( props ) map { cc => 
      Future successful {
        conferenceContext = cc
      }
    }

    super.initializer( rootType, model, props )
  }

  private def checkConferenceContext( props: Map[Symbol, Any] ): V[ActorRef] = {
    val result = for {
      cc <- props get 'ConferenceContext
      r <- scala.util.Try[ActorRef]{ cc.asInstanceOf[ActorRef] }.toOption
    } yield r.successNel

    result getOrElse UnspecifiedConferenceContextError( 'ConferenceContext ).failureNel 
  }


  override val aggregateIdTag: Symbol = 'conference

  override val aggregateRootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName

      override def aggregateRootProps( implicit model: DomainModel ): Props = {
        Conference.props( model, this, conferenceContext )
      }

      // override val toString: String = shardName + "AggregateRootType"
    }
  }


  sealed trait ConferenceProtocol

  case object GetPublishedSeatTypes extends ConferenceProtocol
  case class SeatTypes( values: Seq[SeatType] ) extends ConferenceProtocol

  final case class VerifiedCreateConference private[conference]( conference: ConferenceInfo ) extends ConferenceProtocol

  final case class SlugTaken private[conference]( targetId: ConferenceModule.TID, slug: String ) extends ConferenceProtocol


  case class CreateConference( override val targetId: CreateConference#TID, conference: ConferenceInfo ) extends Command
  case class UpdateConference( override val targetId: UpdateConference#TID, conference: ConferenceInfo ) extends Command

  case class CreateSeat( override val targetId: CreateSeat#TID, seat: SeatType ) extends Command
  case class UpdateSeat( override val targetId: UpdateSeat#TID, seat: SeatType ) extends Command
  case class DeleteSeat( override val targetId: DeleteSeat#TID, seatId: SeatType.TID ) extends Command


  case class Publish( override val targetId: Publish#TID ) extends Command
  case class Unpublish( override val targetId: Unpublish#TID ) extends Command


  //Conference/Conference.Contracts/ConferenceCreated.cs
  case class ConferenceCreated( override val sourceId: ConferenceCreated#TID, conference: ConferenceInfo ) extends Event
  //Conference/Conference.Contracts/ConferenceUpdated.cs
  case class ConferenceUpdated( override val sourceId: ConferenceCreated#TID, conference: ConferenceInfo ) extends Event
  //Conference/Conference.Contracts/ConferencePublished.cs
  case class ConferencePublished( override val sourceId: ConferenceCreated#TID ) extends Event
  //Conference/Conference.Contracts/ConferenceUnpublished.cs
  case class ConferenceUnpublished( override val sourceId: ConferenceCreated#TID ) extends Event
  //Conference/Conference.Contracts/SeatCreated.cs
  case class SeatCreated( override val sourceId: ConferenceCreated#TID, seatType: SeatType ) extends Event
  //Conference/Conference.Contracts/SeatUpdated.cs
  case class SeatUpdated( override val sourceId: ConferenceCreated#TID, seatType: SeatType ) extends Event
  case class SeatDeleted( override val sourceId: ConferenceCreated#TID, seatTypeId: SeatType.TID ) extends Event


  case class ConferenceState(
    id: TID,
    name: String,
    slug: String,
    ownerName: String,
    ownerEmail: String, //DMR: EmailAddress Archetype
    scheduled: joda.Interval,
    seats: Set[SeatType] = Set(),
    description: Option[String] = None,
    location: Option[String] = None,  //DMR: Geolocation Archetype
    tagline: Option[String] = None,
    accessCode: Option[String] = None,
    isPublished: Boolean = false,
    twitterSearch: Option[String] = None
  )

  object ConferenceState {
    def apply( info: ConferenceInfo ): ConferenceState = {
      ConferenceState(
        id = info.id,
        name = info.name,
        slug = info.slug,
        ownerName = info.ownerName,
        ownerEmail = info.ownerEmail, //DMR: EmailAddress Archetype
        scheduled = info.scheduled,
        seats = info.seats,
        description = info.description,
        location = info.location,  //DMR: Geolocation Archetype
        tagline = info.tagline,
        accessCode = info.accessCode,
        twitterSearch = info.twitterSearch
      )
    }


    implicit val stateSpec = new AggregateStateSpecification[ConferenceState] {
      private val seatsLens = lens[ConferenceState] >> 'seats
      
      override def acceptance( state: ConferenceState ): Acceptance = {
        case ConferenceCreated( _, c ) => ConferenceState( c )
        case ConferenceUpdated( _, c ) => ConferenceState( c )
        case ConferencePublished => state.copy( isPublished = true )
        case ConferenceUnpublished => state.copy( isPublished = false )
        case SeatCreated( _, seatType ) => seatsLens.set( state )( state.seats + seatType )
        case SeatUpdated( _, seatType ) => {
          // Set will not update member if it exists (by hashCode), so need to remove and add
          val reduced = state.seats - seatType
          seatsLens.set( state )( reduced + seatType )
        }
        case SeatDeleted( _, seatTypeId ) => {
          val result = for {
            seatType <- state.seats find { _.id == seatTypeId }
          } yield {
            val reduced = state.seats - seatType
            seatsLens.set( state )( reduced )
          }
          result getOrElse state
        }
      }
    }
  }


  object Conference {
    def props( model: DomainModel, meta: AggregateRootType, conferenceContext: ActorRef ): Props = {
      Props( new Conference( model, meta, conferenceContext ) with EventPublisher )
    }

    class ConferenceCreateException( cause: Throwable )
    extends RuntimeException( s"failed to create conference due to: ${cause}", cause )
  }

  class Conference(
    override val model: DomainModel,
    override val meta: AggregateRootType,
    conferenceContext: ActorRef
  ) extends AggregateRoot[ConferenceState] { outer: EventPublisher =>
    import contoso.conference.ConferenceModule.Conference._

    override val trace = Trace( "Conference", log )

    // override val registerBus: RegisterBus = model.registerBus

    override var state: ConferenceState = _

    override def transitionFor( oldState: ConferenceState, newState: ConferenceState ): Transition = {
      case _: ConferenceCreated => context.become( around( draft ) )
      case _: ConferencePublished => context.become( around( published ) )
      case _: ConferenceUnpublished => context.become( around( draft ) )
    }

    // override def pathname: String = self.path.name
    override def receiveCommand: Receive = around( quiescent )

    val quiescent: Receive = LoggingReceive {
      case CreateConference( id, conference ) if !conference.slug.isEmpty => {
        implicit val ec: ExecutionContext = context.system.dispatchers.lookup( "conference-context-dispatcher" )

        val askForSlugStatus = conferenceContext.askretry(
          msg = ConferenceContext.ReserveSlug( conference.slug, conference.id ),
          maxAttempts = 5,
          rate = 250.millis
        ).mapTo[ConferenceContext.SlugStatus]

        askForSlugStatus onComplete {
          case Success( status ) => persist( ConferenceCreated( id, conference ) ) { event => acceptAndPublish( event ) }
          case Failure( ex ) => throw new ConferenceCreateException( ex )
        }
      }
    }


    def draft: Receive = LoggingReceive {
      case UpdateConference( _, conference ) => {
        persist( ConferenceCreated( state.id, conference ) ) { event => acceptAndPublish( event ) }
      }

      case CreateSeat( _, seat ) => {
        persist( SeatCreated( state.id, seat ) ) { event => acceptAndPublish( event ) }
      }

      case UpdateSeat( _, seat ) => {
        persist( SeatUpdated( state.id, seat ) ) { event => acceptAndPublish( event ) }
      }

      case DeleteSeat( _, seatId ) => {
        persist( SeatDeleted( state.id, seatId ) ) { event => acceptAndPublish( event ) }
      }

      case Publish => {
        persist( ConferencePublished( state.id ) ) { event => acceptAndPublish( event ) }
      }
    }

    def published: Receive = LoggingReceive {
      case Unpublish => { persist( ConferenceUnpublished( state.id ) ) { event => acceptAndPublish( event ) } }
    }

    def common: Receive = peds.commons.util.emptyBehavior[Any, Unit]

    // override val unhandled: Receive = {
    //   case x => log info s">>>>> POST UNEXPECTED MESSAGE $x"
    // }
  }


  final case class UnspecifiedConferenceContextError private[conference]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ConferenceContext actor ref required at initialization property [$expectedKey]" ) with ContosoError
}
