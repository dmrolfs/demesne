package demesne.register

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object RegisterSupervisor {
  def props: Props = Props[RegisterSupervisor]

  import scala.language.existentials
  sealed trait Message
  case class RegisterFinder( spec: FinderSpec[_, _] ) extends Message
  case class FinderRegistered( spec: FinderSpec[_, _] ) extends Message


  object FinderRegistration {
    def props(
      supervisor: ActorRef,
      spec: FinderSpec[_, _],
      registrant: ActorRef
    ): Props = Props( new FinderRegistration( supervisor, spec, registrant ) )


    sealed trait RegisterConstituent {
      def category: Symbol
    }

    case object Access extends RegisterConstituent {
      override def category: Symbol = 'RegisterAccess
    }

    case object Relay extends RegisterConstituent {
      override def category: Symbol = 'RegisterRelay
    }

    case object Aggregate extends RegisterConstituent {
      override def category: Symbol = 'RegisterAggregate
    }
  }

  class FinderRegistration(
    supervisor: ActorRef,
    spec: FinderSpec[_, _],
    registrant: ActorRef
  ) extends Actor with ActorLogging {
    import demesne.register.RegisterSupervisor.FinderRegistration._

    val trace = Trace( getClass.safeSimpleName, log )

    implicit val ec: ExecutionContext = context.dispatcher //okay to use actor's dispatcher
    implicit val askTimeout: Timeout = 3.seconds

    case class RegisterConstituentRef( constituent: RegisterConstituent, path: ActorPath, props: Props ) {
      def name: String = path.name
    }


    sealed trait RegistrationWorkflow

    case class Survey(
      toFind: List[RegisterConstituentRef],
      toStart: List[RegisterConstituentRef]
    ) extends RegistrationWorkflow

    case class Startup( pieces: List[RegisterConstituentRef] ) extends RegistrationWorkflow


    val pieces: List[RegisterConstituentRef] = constituentsFor( spec )
    self ! Survey( toFind = pieces, toStart = List() )
    pieces foreach { p => context.actorSelection( p.path ) ! Identify( p.name ) }

    override def receive: Receive = survey

    val survey: Receive = LoggingReceive {
      case Survey(Nil, toStart) => trace.block( s"FinderRegistration.survey::Survey(Nil, $toStart)" ) {
        context become startup( toStart )
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

    def startup( pieces: List[RegisterConstituentRef] ): Receive = LoggingReceive {
      case Startup( Nil ) => trace.block( s"FinderRegistration.startup::Startup(Nil)" ) {
        registrant ! FinderRegistered( spec )
        context stop self
      }

      case Startup( pieces ) => trace.block( s"FinderRegistration.startup::Startup($pieces)" ) {
        val p = pieces.head
        val createPiece = StartChild( props = p.props, name = p.name )
        supervisor ? createPiece map {
          case m: ChildStarted => Startup( pieces.tail )
          case m => log error s"failed to create register piece: ${p}"  //todo consider retry state via ctx.become
        } pipeTo self
      }
    }

    def constituentsFor( spec: FinderSpec[_, _] ): List[RegisterConstituentRef] = trace.block( s"constituentsFor($spec))" ) {
      val baseline = supervisor.path.name + "/" + spec.topic + "/"

      def pathFor( constituent: RegisterConstituent ): ActorPath = {
        ActorPath.fromString( baseline + constituent.category.name )
      }

      val aggregatePath = pathFor( Aggregate )

      List(
        RegisterConstituentRef( Access, pathFor( Access ), spec.accessProps ),
        RegisterConstituentRef( Aggregate, aggregatePath, spec.aggregateProps ),
        RegisterConstituentRef( Relay, pathFor( Relay ), spec relayProps aggregatePath )
      )
    }
  }
}

/**
 * Created by damonrolfs on 11/6/14.
 */
class RegisterSupervisor extends IsolatedDefaultSupervisor with OneForOneStrategyFactory with ActorLogging {
  import demesne.register.RegisterSupervisor._

  val trace = Trace( getClass.safeSimpleName, log )

  override def childStarter(): Unit = { }

  override def receive: Receive = super.receive orElse register

  val register: Receive = LoggingReceive {
    case RegisterFinder( spec ) => trace.block( s"RegisterSupervisor.register:RegisterFinder($spec)" ) {
      context.actorOf( FinderRegistration.props( supervisor = self, spec = spec, registrant = sender() ) )
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