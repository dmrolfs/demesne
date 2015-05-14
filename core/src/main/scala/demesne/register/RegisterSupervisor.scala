package demesne.register

import akka.actor._
import akka.event.LoggingReceive
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.typesafe.scalalogging.StrictLogging
import demesne.{register, AggregateRootType}
import demesne.register.RegisterSupervisor.ConstituencyProvider
import peds.akka.envelope.Envelope
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.akka.supervision.{IsolatedDefaultSupervisor, OneForOneStrategyFactory}
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


/**
 * Created by damonrolfs on 11/6/14.
 */
class RegisterSupervisor( bus: RegisterBus )
  extends IsolatedDefaultSupervisor with OneForOneStrategyFactory with ActorLogging {
  outer: ConstituencyProvider =>

  import demesne.register.RegisterSupervisor._

  val trace = Trace( "RegisterSupervisor", log )

  override def childStarter(): Unit = { }

  override def receive: Receive = super.receive orElse register

  val register: Receive = LoggingReceive {
    case RegisterFinder( rootType, spec ) => trace.block( s"register:RegisterFinder($rootType, $spec)" ) {
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

object RegisterSupervisor extends StrictLogging {
  val trace = Trace( "RegisterSupervisor", logger )

  def props( bus: RegisterBus ): Props = Props( new RegisterSupervisor( bus ) with ConstituencyProvider )

  import scala.language.existentials
  sealed trait Message
  case class RegisterFinder( rootType: AggregateRootType, spec: FinderSpec[_, _] ) extends Message
  case class FinderRegistered( agentRef: ActorRef, rootType: AggregateRootType, spec: FinderSpec[_, _] ) extends Message


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
        classifier => {
          val (ctx, clazz) = classifier
          trace( s"Relay[$constituent] register with Akka EventStream for class=$clazz")
          ctx.system.eventStream.subscribe( constituent, clazz )
          ctx.system.eventStream.subscribe( constituent, classOf[Envelope] )
        },
        classifier => {
          val (bus, name) = classifier
          trace( s"Relay[$constituent] register with bus[$bus] for name=$name")
          bus.subscribe( constituent, name )
        }
      )
    }
  }

  case object Aggregate extends RegisterConstituent {
    override val category: Symbol = 'RegisterAggregate
  }


  case class RegisterConstituentRef( constituent: RegisterConstituent, path: ActorPath, props: Props ) {
    def name: String = path.name
    override def toString: String = s"${getClass.safeSimpleName}(${constituent}, ${path})"
  }


  trait ConstituencyProvider { outer: Actor =>
    def pathFor(
      registrantType: AggregateRootType,
      spec: FinderSpec[_,_]
    )(
      constituent: RegisterConstituent
    ): ActorPath = ActorPath.fromString(
      self.path + "/" + constituent.category.name + "-" + spec.topic( registrantType )
    )

    def constituencyFor( registrantType: AggregateRootType, spec: FinderSpec[_, _] ): List[RegisterConstituentRef] = {
      val p = pathFor( registrantType, spec ) _
      val aggregatePath = p( Aggregate )

      List(
        RegisterConstituentRef( Relay, p( Relay ), spec relayProps aggregatePath ),
        RegisterConstituentRef( Agent, p( Agent ), spec agentProps registrantType ),
        RegisterConstituentRef( Aggregate, aggregatePath, spec aggregateProps registrantType )
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

    var constituentRefs: Map[RegisterConstituent, ActorRef] = Map()

    override def receive: Receive = survey

    val survey: Receive = LoggingReceive {
      case Survey(Nil, toStart) => {
        log debug s"""starting for spec[${spec}]: ${toStart.map(_.name).mkString("[",",","]")}"""
        self ! Startup(toStart)
        context become startup
      }

      case Survey(toFind, toStart) => {
        val piece = toFind.head
        context.actorSelection(piece.path) ? Identify(piece.name) map {
          case ActorIdentity(_, None) => {
            log debug s"${piece.name} not found for ${spec}"
            Survey( toFind.tail, piece :: toStart )
          }

          case ActorIdentity(_, Some(ref) ) => {
            log debug s"${piece.name} found for ${spec}"
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
          log debug s"sending WaitingForStart to $cref"
          cref ! register.WaitingForStart
        }
        context become verify( constituentRefs )
      }

      case Startup( pieces ) => {
        val p = pieces.head
        log info s"starting for spec[${spec}]: ${p.name}"
        val createPiece = StartChild( props = p.props, name = p.name )
        supervisor ? createPiece map {
          case ChildStarted( child ) => {
            p.constituent.postStart( child, subscription )
            constituentRefs += (p.constituent -> child )
            Startup( pieces.tail )
          }

          case m => log error s"failed to create register piece: ${p}"  //todo consider retry state via ctx.become
        } pipeTo self
      }
    }

    def verify( toCheck: Map[RegisterConstituent, ActorRef] ): Receive = LoggingReceive {
      case register.Started => {
        val c = sender()
        val verified = toCheck find {
          _._2 == c
        } getOrElse {
          throw new IllegalStateException(s"failed to recognize register constituent[$c] in toCheck[${toCheck}}]")
        }

        log info s"verified constituent: ${verified}"
        val next = toCheck - verified._1
        handleNext(toCheck - verified._1)
      }
    }

    def handleNext( next: Map[RegisterConstituent, ActorRef] ): Unit = {
      if ( !next.isEmpty ) context become verify( next )
      else {
        val msg = FinderRegistered( constituentRefs( Agent ), registrantType, spec )
        log debug s"sending: $registrant ! $msg"
        registrant ! msg
        context stop self
      }
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