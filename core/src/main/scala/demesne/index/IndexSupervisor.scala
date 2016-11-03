package demesne.index

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import demesne.{index, AggregateRootType}
import demesne.index.IndexSupervisor.ConstituencyProvider
import peds.akka.envelope.Envelope
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace
import peds.commons.util._


/**
 * Created by damonrolfs on 11/6/14.
 */
class IndexSupervisor(bus: IndexBus )
  extends IsolatedDefaultSupervisor with OneForOneStrategyFactory with ActorLogging {
  outer: ConstituencyProvider =>

  import demesne.index.IndexSupervisor._

  val trace = Trace( "IndexSupervisor", log )

  override def childStarter(): Unit = { }

  override def receive: Receive = super.receive orElse register

  val register: Receive = LoggingReceive {
    case RegisterIndex( rootType, spec ) => trace.block( s"index:RegisterIndex($rootType, $spec)" ) {
      val subscription: SubscriptionClassifier = spec.relaySubscription match {
        case ContextChannelSubscription( channel ) => Left( (context, channel) )
        case IndexBusSubscription => Right( (bus, spec.relayClassifier( rootType ) ) )
      }

      context.actorOf(
        IndexRegistration.props(
          supervisor = self,
          constituency = constituencyFor( rootType, spec ),
          subscription = subscription,
          spec = spec,
          registrant = sender(),
          registrantType = rootType
        )
      )
    }
  }
}

object IndexSupervisor extends StrictLogging {
  val trace = Trace( "IndexSupervisor", logger )

  def props( bus: IndexBus ): Props = Props( new IndexSupervisor( bus ) with ConstituencyProvider )

  import scala.language.existentials
  sealed trait Message
  case class RegisterIndex( rootType: AggregateRootType, spec: IndexSpecification ) extends Message
  case class IndexRegistered( agentRef: ActorRef, rootType: AggregateRootType, spec: IndexSpecification ) extends Message


  type ContextClassifier = (ActorContext, Class[_])
  type BusClassifier = (IndexBus, String)
  type SubscriptionClassifier = Either[ContextClassifier, BusClassifier]


  sealed trait IndexConstituent {
    def category: Symbol
    def postStart( constituent: ActorRef, subscription: SubscriptionClassifier ): Boolean = true
  }

  case object Agent extends IndexConstituent {
    override val category: Symbol = 'IndexAgent
  }

  case object Relay extends IndexConstituent {
    override val category: Symbol = 'IndexRelay

    override def postStart( constituent: ActorRef, subscription: SubscriptionClassifier ): Boolean = {
      subscription.fold(
        classifier => {
          val (ctx, clazz) = classifier
          logger.debug( "Relay[{}] index with Akka EventStream for class={}", constituent, clazz )
          ctx.system.eventStream.subscribe( constituent, clazz )
          ctx.system.eventStream.subscribe( constituent, classOf[Envelope] )
        },
        classifier => {
          val (bus, name) = classifier
          logger.debug( "Relay[{}] index with bus[{}] for name={}", constituent, bus, name )
          bus.subscribe( constituent, name )
        }
      )
    }
  }

  case object Aggregate extends IndexConstituent {
    override val category: Symbol = 'IndexAggregate
  }


  case class RegisterConstituentRef( constituent: IndexConstituent, path: ActorPath, props: Props ) {
    def name: String = path.name
    override def toString: String = s"${getClass.safeSimpleName}(${constituent}, ${path})"
  }


  trait ConstituencyProvider { outer: Actor =>
    def pathFor(
      registrantType: AggregateRootType,
      spec: IndexSpecification
    )(
      constituent: IndexConstituent
    ): ActorPath = {
      ActorPath.fromString( self.path + "/" + constituent.category.name + "-" + spec.topic(registrantType) )
    }

    def constituencyFor( registrantType: AggregateRootType, spec: IndexSpecification ): List[RegisterConstituentRef] = {
      val p = pathFor( registrantType, spec ) _
      val aggregatePath = p( Aggregate )

      List(
        RegisterConstituentRef( Relay, p( Relay ), spec relayProps aggregatePath ),
        RegisterConstituentRef( Agent, p( Agent ), spec agentProps registrantType ),
        RegisterConstituentRef( Aggregate, aggregatePath, spec aggregateProps registrantType )
      )
    }
  }


  object IndexRegistration {
    def props(
      supervisor: ActorRef,
      constituency: List[RegisterConstituentRef],
      subscription: SubscriptionClassifier,
      spec: IndexSpecification,
      registrant: ActorRef,
      registrantType: AggregateRootType
    ): Props = Props( new IndexRegistration( supervisor, constituency, subscription, spec, registrant, registrantType ) )
  }

  class IndexRegistration(
    supervisor: ActorRef,
    constituency: List[RegisterConstituentRef],
    subscription: SubscriptionClassifier,
    spec: IndexSpecification,
    registrant: ActorRef,
    registrantType: AggregateRootType
  ) extends Actor with ActorLogging {

    val trace = Trace( getClass.safeSimpleName, log )

    implicit val ec: ExecutionContext = context.dispatcher //okay to use actor's dispatcher
    implicit val askTimeout: Timeout = 3.seconds //todo move into configuration


    sealed trait RegistrationWorkflow

    case class Survey(
      toFind: List[RegisterConstituentRef],
      toStart: List[RegisterConstituentRef]
    ) extends RegistrationWorkflow

    case class Startup( pieces: List[RegisterConstituentRef] ) extends RegistrationWorkflow


    self ! Survey( toFind = constituency, toStart = List() )
    constituency foreach { c => context.actorSelection( c.path ) ! Identify( c.name ) }

    var constituentRefs: Map[IndexConstituent, ActorRef] = Map( )

    override def receive: Receive = survey

    val survey: Receive = LoggingReceive {
      case Survey(Nil, toStart) => {
        log.debug( """starting for spec[{}]: {}""", spec, toStart.map(_.name).mkString("[",",","]") )
        self ! Startup(toStart)
        context become startup
      }

      case Survey(toFind, toStart) => {
        val piece = toFind.head
        context.actorSelection(piece.path) ? Identify(piece.name) map {
          case ActorIdentity(_, None) => {
            log.debug( "{} not found for {}", piece.name, spec )
            Survey( toFind.tail, piece :: toStart )
          }

          case ActorIdentity(_, Some(ref) ) => {
            log.debug( "{} found for {}", piece.name, spec )
            constituentRefs += (piece.constituent -> ref)
            Survey( toFind.tail, toStart )
          }
        } pipeTo self
      }
    }

    val startup: Receive = LoggingReceive {
//after startup move into verify (waits until it's all setup before ultimately returning from registration)
//ref akka concurrency for controlled startup pattern
      case Startup( Nil ) => {
        constituentRefs.values foreach { cref =>
          log.debug( "sending WaitForStart to {}" , cref )
          cref ! index.WaitingForStart
        }
        context become verify( constituentRefs )
      }

      case Startup( pieces ) => {
        val p = pieces.head
        log.debug( "starting for spec[{}]: {}", spec, p.name )
        val createPiece = StartChild( props = p.props, name = p.name )
        supervisor ? createPiece map {
          case ChildStarted( child ) => {
            p.constituent.postStart( child, subscription )
            constituentRefs += (p.constituent -> child )
            Startup( pieces.tail )
          }

          case m => log.error( "failed to create index piece: [{}]", p )  //todo consider retry state via ctx.become
        } pipeTo self
      }
    }

    def verify( toCheck: Map[IndexConstituent, ActorRef] ): Receive = LoggingReceive {
      case index.Started => {
        val c = sender()
        val verified = toCheck find {
          _._2 == c
        } getOrElse {
          throw new IllegalStateException(s"failed to recognize index constituent[$c] in toCheck[${toCheck}}]")
        }

        log.debug( "verified constituent: {}", verified )
        val next = toCheck - verified._1
        handleNext(toCheck - verified._1)
      }
    }

    def handleNext( next: Map[IndexConstituent, ActorRef] ): Unit = {
      if ( !next.isEmpty ) context become verify( next )
      else {
        val msg = IndexRegistered( constituentRefs( Agent ), registrantType, spec )
        log.debug( "sending: () ! {}", registrant, msg )
        registrant ! msg
        context stop self
      }
    }
  }
}
