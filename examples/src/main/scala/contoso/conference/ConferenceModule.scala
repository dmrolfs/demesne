package contoso.conference

import akka.Done

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive

import scalaz._
import Scalaz._
import shapeless._
import com.github.nscala_time.time.{Imports => joda}
import omnibus.commons.Valid
import omnibus.akka.AskRetry._
import omnibus.akka.publish._
import omnibus.commons.identifier._
import omnibus.commons.log.Trace
import demesne._
import demesne.repository._


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


case class ConferenceState(
  id: ConferenceState#TID,
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
) {
  type ID = ShortUUID
  type TID = TaggedID[ID]
}

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


  implicit val identifying = new Identifying[ConferenceState] with ShortUUID.ShortUuidIdentifying[ConferenceState] {
    override val idTag: Symbol = 'conference
    override def tidOf( s: ConferenceState ): TID = s.id
  }
}



object ConferenceModule extends AggregateRootModule[ConferenceState, ConferenceState#ID] { module =>
  val trace = Trace[ConferenceModule.type]

  object Repository {
    def props( model: DomainModel ): Props = Props( new Repository( model ) )
  }

  class Repository( model: DomainModel )
  extends EnvelopingAggregateRootRepository( model, ConferenceType ) with ClusteredAggregateContext {
    import demesne.repository.{ StartProtocol => SP }
    var conferenceContext: ActorRef = model.system.deadLetters

    override def aggregateProps: Props = Conference.props( model, rootType, conferenceContext )

    override def doLoad(): SP.Loaded = {
      logger.info( "loading" )
      SP.Loaded( rootType, dependencies = Set(ConferenceContext.ResourceKey) )
    }

    override def doInitialize( resources: Map[Symbol, Any] ): Valid[Done] = {
      checkConferenceContext( resources ) map { confCtx =>
        logger.info( "initializing conference context:[{}]", confCtx.path.name )
        conferenceContext = confCtx
        Done
      }
    }

    private def checkConferenceContext( resources: Map[Symbol, Any] ): Valid[ActorRef] = {
      val result = for {
        cc <- resources get ConferenceContext.ResourceKey
        r <- scala.util.Try[ActorRef]{ cc.asInstanceOf[ActorRef] }.toOption
      } yield r.successNel[Throwable]

      result getOrElse Validation.failureNel( UnspecifiedConferenceContextError('ConferenceContext) )
    }
  }

  object ConferenceType extends AggregateRootType {
    override val name: String = module.shardName
    override type S = ConferenceState
    override val identifying: Identifying[S] = ConferenceState.identifying
    override def repositoryProps( implicit model: DomainModel ): Props = Repository.props( model )
  }

  override val rootType: AggregateRootType = ConferenceType


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
  ) extends AggregateRoot[ConferenceState, ShortUUID] with AggregateRoot.Provider { outer: EventPublisher =>
    import ConferenceProtocol._
    import contoso.conference.ConferenceModule.Conference._

    private val trace = Trace( "Conference", log )

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
      case UpdateConference( _, conference ) => persist( ConferenceCreated( state.id, conference ) ) { acceptAndPublish }
      case CreateSeat( _, seat ) => persist( SeatCreated( state.id, seat ) ) { acceptAndPublish }
      case UpdateSeat( _, seat ) => persist( SeatUpdated( state.id, seat ) ) { acceptAndPublish }
      case DeleteSeat( _, seatId ) => persist( SeatDeleted( state.id, seatId ) ) { acceptAndPublish }
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

    def common: Receive = omnibus.commons.util.emptyBehavior[Any, Unit]
  }


  final case class UnspecifiedConferenceContextError private[conference]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ConferenceContext actor ref required at initialization property [$expectedKey]" ) with ContosoError
}
