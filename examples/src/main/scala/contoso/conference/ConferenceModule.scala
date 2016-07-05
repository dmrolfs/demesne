package contoso.conference

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect._
import scala.util.{Failure, Success}
import scala.concurrent.Future
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive

import scalaz._
import Scalaz._
import shapeless._
import com.github.nscala_time.time.{Imports => joda}
import peds.commons.{TryV, Valid}
import peds.akka.AskRetry._
import peds.akka.publish._
import peds.commons.identifier._
import peds.commons.log.Trace
import demesne._


object ConferenceProtocol extends AggregateProtocol[ShortUUID] {
  sealed trait ConferenceMessage
  sealed trait ConferenceCommand extends Command with ConferenceMessage
  sealed trait ConferenceEvent extends Event with ConferenceMessage

  case object GetPublishedSeatTypes extends ConferenceMessage
  case class SeatTypes( values: Seq[SeatType] ) extends ConferenceMessage

  final case class VerifiedCreateConference private[conference]( conference: ConferenceInfo ) extends ConferenceMessage

  final case class SlugTaken private[conference]( targetId: ConferenceModule.TID, slug: String ) extends ConferenceMessage


  case class CreateConference( override val targetId: CreateConference#TID, conference: ConferenceInfo ) extends ConferenceCommand
  case class UpdateConference( override val targetId: UpdateConference#TID, conference: ConferenceInfo ) extends ConferenceCommand

  case class CreateSeat( override val targetId: CreateSeat#TID, seat: SeatType ) extends ConferenceCommand
  case class UpdateSeat( override val targetId: UpdateSeat#TID, seat: SeatType ) extends ConferenceCommand
  case class DeleteSeat( override val targetId: DeleteSeat#TID, seatId: SeatType.TID ) extends ConferenceCommand


  case class Publish( override val targetId: Publish#TID ) extends ConferenceCommand
  case class Unpublish( override val targetId: Unpublish#TID ) extends ConferenceCommand


  //Conference/Conference.Contracts/ConferenceCreated.cs
  case class ConferenceCreated( override val sourceId: ConferenceCreated#TID, conference: ConferenceInfo ) extends ConferenceEvent
  //Conference/Conference.Contracts/ConferenceUpdated.cs
  case class ConferenceUpdated( override val sourceId: ConferenceUpdated#TID, conference: ConferenceInfo ) extends ConferenceEvent
  //Conference/Conference.Contracts/ConferencePublished.cs
  case class ConferencePublished( override val sourceId: ConferencePublished#TID ) extends ConferenceEvent
  //Conference/Conference.Contracts/ConferenceUnpublished.cs
  case class ConferenceUnpublished( override val sourceId: ConferenceUnpublished#TID ) extends ConferenceEvent
  //Conference/Conference.Contracts/SeatCreated.cs
  case class SeatCreated( override val sourceId: SeatCreated#TID, seatType: SeatType ) extends ConferenceEvent
  //Conference/Conference.Contracts/SeatUpdated.cs
  case class SeatUpdated( override val sourceId: SeatUpdated#TID, seatType: SeatType ) extends ConferenceEvent
  case class SeatDeleted( override val sourceId: SeatDeleted#TID, seatTypeId: SeatType.TID ) extends ConferenceEvent
}

//object ConferenceModule extends AggregateRootModule[ShortUUID] { module =>
object ConferenceModule extends AggregateRootModule { module =>
  //DMR move these into common AggregateModuleCompanion trait
  val trace = Trace[ConferenceModule.type]

  override type ID = ShortUUID
  override def nextId: TryV[TID] = implicitly[Identifying[ConferenceState]].nextIdAs[TID]

  var conferenceContext: ActorRef = _

  override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : Valid[Future[Unit]] = {
    checkConferenceContext( props ) map { cc => 
      Future successful {
        conferenceContext = cc
      }
    }

    super.initializer( rootType, model, props )
  }

  private def checkConferenceContext( props: Map[Symbol, Any] ): Valid[ActorRef] = {
    val result = for {
      cc <- props get 'ConferenceContext
      r <- scala.util.Try[ActorRef]{ cc.asInstanceOf[ActorRef] }.toOption
    } yield r.successNel[Throwable]

    result getOrElse Validation.failureNel( UnspecifiedConferenceContextError('ConferenceContext) )
  }


  override val aggregateIdTag: Symbol = 'conference

  override val rootType: AggregateRootType = {
    new AggregateRootType {
      override val name: String = module.shardName

      override def aggregateRootProps( implicit model: DomainModel ): Props = {
        Conference.props( model, this, conferenceContext )
      }
    }
  }


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

    val seatsLens = lens[ConferenceState] >> 'seats
  }

  implicit val conferenceIdentifying: Identifying[ConferenceState] = {
    new Identifying[ConferenceState] with ShortUUID.ShortUuidIdentifying[ConferenceState] {
      override def idOf( o: ConferenceState ): TID = o.id
      override val idTag: Symbol = ConferenceModule.aggregateIdTag
    }
  }

  object Conference {
    def props( model: DomainModel, rt: AggregateRootType, conferenceContext: ActorRef ): Props = {
      Props( new Conference( model, rt, conferenceContext ) with EventPublisher )
    }

    class ConferenceCreateException( cause: Throwable )
    extends RuntimeException( s"failed to create conference due to: ${cause}", cause )
  }

  class Conference(
    override val model: DomainModel,
    override val rootType: AggregateRootType,
    conferenceContext: ActorRef
  ) extends AggregateRoot[ConferenceState, ShortUUID] { outer: EventPublisher =>
    import ConferenceProtocol._
    import contoso.conference.ConferenceModule.Conference._

    override val trace = Trace( "Conference", log )

    override def parseId( idstr: String ): TID = {
      val identifying = implicitly[Identifying[ConferenceState]]
      identifying.safeParseId[ID]( idstr )( classTag[ShortUUID] )
    }

    override var state: ConferenceState = _

    override val acceptance: Acceptance = {
      case ( ConferenceCreated(_, c), _ ) => ConferenceState( c )
      case ( ConferenceUpdated(_, c), _ ) => ConferenceState( c )
      case ( _: ConferencePublished, state ) => state.copy( isPublished = true )
      case ( _: ConferenceUnpublished, state ) => state.copy( isPublished = false )
      case ( SeatCreated(_, seatType), state ) => ConferenceState.seatsLens.set( state )( state.seats + seatType )
      case ( SeatUpdated(_, seatType), state ) => {
        // Set will not update member if it exists (by hashCode), so need to remove and add
        val reduced = state.seats - seatType
        ConferenceState.seatsLens.set( state )( reduced + seatType )
      }
      case ( SeatDeleted(_, seatTypeId), state ) => {
        val result = for {
          seatType <- state.seats find { _.id == seatTypeId }
        } yield {
          val reduced = state.seats - seatType
          ConferenceState.seatsLens.set( state )( reduced )
        }
        result getOrElse state
      }
    }

    override def receiveCommand: Receive = around( quiescent )

    import contoso.conference.{ ConferenceContextProtocol => CCP }

    val quiescent: Receive = LoggingReceive {
      case CreateConference( id, conference ) if !conference.slug.isEmpty => {
        implicit val ec: ExecutionContext = context.system.dispatchers.lookup( "conference-context-dispatcher" )

        val askForSlugStatus = conferenceContext.askretry(
          msg = CCP.ReserveSlug( conference.slug, conference.id ),
          maxAttempts = 5,
          rate = 250.millis
        ).mapTo[CCP.SlugStatus]

        askForSlugStatus onComplete {
          case Success( status ) => persist( ConferenceCreated( id, conference ) ) { event => 
            acceptAndPublish( event ) 
            context.become( around( draft ) )
          }

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
        persist( ConferencePublished( state.id ) ) { event => 
          acceptAndPublish( event ) 
          context.become( around( published ) )
        }
      }
    }

    def published: Receive = LoggingReceive {
      case Unpublish => persist( ConferenceUnpublished( state.id ) ) { event => 
        acceptAndPublish( event ) 
        context.become( around( draft ) )
      } 
    }

    def common: Receive = peds.commons.util.emptyBehavior[Any, Unit]

    // override val unhandled: Receive = {
    //   case x => log info s">>>>> POST UNEXPECTED MESSAGE $x"
    // }
  }


  final case class UnspecifiedConferenceContextError private[conference]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ConferenceContext actor ref required at initialization property [$expectedKey]" ) with ContosoError
}
