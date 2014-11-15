package demesne.register

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import demesne.AggregateRootType
import demesne.register.RegisterSupervisor.ConstituencyProvider
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object RegisterSupervisor extends StrictLogging {
  val trace = Trace[RegisterSupervisor]

  def props( bus: RegisterBus ): Props = Props( new RegisterSupervisor( bus ) with ConstituencyProvider )

  import scala.language.existentials
  sealed trait Message
  case class RegisterFinder( rootType: AggregateRootType, spec: FinderSpec[_, _] ) extends Message
  case class FinderRegistered( rootType: AggregateRootType, spec: FinderSpec[_, _] ) extends Message


  type ContextClassifier = (ActorContext, Class[_])
  type BusClassifier = (RegisterBus, String)
  type SubscriptionClassifier = Either[ContextClassifier, BusClassifier]


  sealed trait RegisterConstituent {
    def category: Symbol
    def postStart( constituent: ActorRef, subscription: SubscriptionClassifier ): Boolean = true
  }

  case object Agent extends RegisterConstituent {
    override val category: Symbol = 'RegisterAgent
  }

  case object Relay extends RegisterConstituent {
    override val category: Symbol = 'RegisterRelay

    override def postStart( constituent: ActorRef, subscription: SubscriptionClassifier ): Boolean = {
      subscription.fold(
        classifier => { classifier._1.system.eventStream.subscribe( constituent, classifier._2 ) },
        classifier => { classifier._1.subscribe( constituent, classifier._2 ) }
      )
    }
  }

  case object Aggregate extends RegisterConstituent {
    override val category: Symbol = 'RegisterAggregate
  }


  case class RegisterConstituentRef( constituent: RegisterConstituent, path: ActorPath, props: Props ) {
    def name: String = path.name
  }

  trait ConstituencyProvider { outer: Actor =>
    def constituencyFor( registrantType: AggregateRootType, spec: FinderSpec[_, _] ): List[RegisterConstituentRef] = trace.block( s"constituentsFor($spec))" ) {
      def pathFor( constituent: RegisterConstituent ): ActorPath = {
        ActorPath.fromString(
          self.path + "/" + constituent.category.name + "_" + spec.name.name + "-" + spec.topic(registrantType)
        )
      }

      val aggregatePath = pathFor( Aggregate )

      List(
        RegisterConstituentRef( Agent, pathFor( Agent ), spec agentProps registrantType ),
        RegisterConstituentRef( Aggregate, aggregatePath, spec aggregateProps registrantType ),
        RegisterConstituentRef( Relay, pathFor( Relay ), spec relayProps aggregatePath )
      )
    }
  }


  object FinderRegistration {
    def props(
      supervisor: ActorRef,
      constituency: List[RegisterConstituentRef],
      subscription: SubscriptionClassifier,
      spec: FinderSpec[_, _],
      registrant: ActorRef,
      registrantType: AggregateRootType
    ): Props = Props( new FinderRegistration( supervisor, constituency, subscription, spec, registrant, registrantType ) )
  }

  class FinderRegistration(
    supervisor: ActorRef,
    constituency: List[RegisterConstituentRef],
    subscription: SubscriptionClassifier,
    spec: FinderSpec[_, _],
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

    override def receive: Receive = survey

    val survey: Receive = LoggingReceive {
      case Survey(Nil, toStart) => trace.block( s"FinderRegistration.survey::Survey(Nil, $toStart)" ) {
        context become startup
        self ! Startup(toStart)
      }

      case Survey(toFind, toStart) => trace.block( s"FinderRegistration.survey::Survey($toFind, $toStart)" ) {
        val piece = toFind.head
        context.actorSelection(piece.path) ? Identify(piece.name) map {
          case ActorIdentity(_, None) => trace.block( s"FinderRegistration.survey::Survey::ActorIdentity(_,None)" ) {
log error s"piece not found: $piece"
            Survey(toFind.tail, piece :: toStart)
          }

          case m => trace.block( s"FinderRegistration.survey::Survey::$m" ) {
log error s"piece found: $piece"
            Survey(toFind.tail, toStart)
          }
        } pipeTo self
      }
    }

    val startup: Receive = LoggingReceive {
      case Startup( Nil ) => trace.block( s"FinderRegistration.startup::Startup(Nil)" ) {
        log warning s">>>> sending: $registrant ! FinderRegistered($registrantType, $spec)"
        registrant ! FinderRegistered( registrantType, spec )
//trace.block( "#### SANITY CHECK ####" ) {
//  val check = scala.concurrent.Await.result( self ? Survey( pieces, Nil ), askTimeout.duration )
//  log error s"##### SANITY CHECK FOUND: $check"
//}
        context stop self
      }

      case Startup( pieces ) => trace.block( s"FinderRegistration.startup::Startup($pieces)" ) {
        val p = pieces.head
        val createPiece = StartChild( props = p.props, name = p.name )
        supervisor ? createPiece map {
          case ChildStarted( child ) => {
            p.constituent.postStart( child, subscription )
            Startup( pieces.tail )
          }

          case m => log error s"failed to create register piece: ${p}"  //todo consider retry state via ctx.become
        } pipeTo self
      }

      case Survey(toFind, toStart) => trace.block( s"FinderRegistration.survey::Survey($toFind, $toStart)" ) {
        val piece = toFind.head
        context.actorSelection(piece.path) ? Identify(piece.name) map {
          case ActorIdentity(_, None) => trace.block( s"FinderRegistration.survey::Survey::ActorIdentity(_,None)" ) {
            log error s"piece not found: $piece"
            Survey(toFind.tail, piece :: toStart)
          }

          case m => trace.block( s"FinderRegistration.survey::Survey::$m" ) {
            log error s"piece found: $piece"
            Survey(toFind.tail, toStart)
          }
        } pipeTo self
      }
    }
  }
}

/**
 * Created by damonrolfs on 11/6/14.
 */
class RegisterSupervisor( bus: RegisterBus )
extends IsolatedDefaultSupervisor with OneForOneStrategyFactory with ActorLogging {
  outer: ConstituencyProvider =>

  import demesne.register.RegisterSupervisor._

  val trace = Trace( getClass.safeSimpleName, log )

  override def childStarter(): Unit = { }

  override def receive: Receive = super.receive orElse register

  val register: Receive = LoggingReceive {
    case RegisterFinder( rootType, spec ) => trace.block( s"RegisterSupervisor.register:RegisterFinder($rootType, $spec)" ) {
      val subscription: SubscriptionClassifier = spec.relaySubscription match {
        case ContextChannelSubscription( channel ) => Left( (context, channel) )
        case RegisterBusSubscription => Right( (bus, spec.relayClassifier(rootType) ) )
      }

      context.actorOf(
        FinderRegistration.props(
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


////val system = ActorSystem( name="system", config )
//val summary = system.actorOf( RegisterLocalSummary.props[String, Int]( "register" ), "summary" )
////val subPath = summary.path.toStringWithoutAddress
//
//val register = system.actorOf( RegisterAggregate.props[String, Int]( "register" ), "register" )
//
//case class Foo( value: String )
//case class Bar( value: Int )
//
//val extractor: PartialFunction[Any, (String, Int)] = {
//case Foo( value ) => (value+"-fooKey", value.hashCode)
//case Bar( value ) => (value.toString+"-barKey", value.hashCode)
//}
//
//val bus = new RegisterBus
//val relay = system.actorOf( Relay.props( registerPath = register.path, extractor = extractor ), "relay" )
//bus.subscribe( relay, "/record" )
//
//val reg = summary ? GetRegister
//val agent = Await.result( reg.mapTo[RegisterProxy], 3.seconds ).mapTo[String, Int]
//
//
//class Zed( bus: RegisterBus ) extends Actor {
//  override def receive = { case m => bus.publish( RegisterBus.RecordingEvent( topic = "/record", recording = m ) ) }
//}
//
//val z = system.actorOf( Props( new Zed( bus ) ), "zed" )
//z ! Foo( "dmr" )
//IS Foo getting into Agent????