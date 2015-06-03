package demesne.register

import akka.actor.FSM.{CurrentState, Transition}
import akka.actor._
import akka.contrib.pattern.ReliableProxy
import akka.contrib.pattern.ReliableProxy.Connecting
import akka.event.LoggingReceive
import peds.akka.envelope.Envelope
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._


object RegisterRelay extends com.typesafe.scalalogging.LazyLogging {
  def props( registerAggregatePath: ActorPath, extractor: KeyIdExtractor ): Props = {
    Props( new RegisterRelay( registerAggregatePath, extractor) )
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterRelay( registerAggregatePath: ActorPath, extractor: KeyIdExtractor )
extends Actor
with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  val fullExtractor: KeyIdExtractor = {
    case m if extractor.isDefinedAt( m ) => extractor( m )
    case e @ Envelope( payload, _ ) if extractor.isDefinedAt( payload ) => extractor( payload )
  }

  //todo move into configuration retry timeout
  val proxy: ActorRef = context.actorOf(
    ReliableProxy.props( targetPath = registerAggregatePath, retryAfter = 100.millis )
  )
  proxy ! FSM.SubscribeTransitionCallBack( self )

  //todo: move into configuration
  override val receive: Receive = connecting( List() )

  def connecting( waiting: List[ActorRef] ): Receive = LoggingReceive {
    case CurrentState( _, state ) if state != Connecting => {
      //      log debug s"proxy.transition( $p, $from -> $to )"
      log debug s"Relay connected to register aggregate at ${registerAggregatePath}"
      proxy ! WaitingForStart
      context become starting( waiting )
    }

    case Transition( _, Connecting, _) => {
      //      log debug s"proxy.transition( $p, $from -> $to )"
      log debug s"Relay connected to register aggregate at ${registerAggregatePath}"
      proxy ! WaitingForStart
      context become starting( waiting )
    }

    case WaitingForStart => {
      log debug s"adding to relay's wait queue: ${sender()}"
      context become connecting( List( sender() ) )
    }
  }

  def starting( waiting: List[ActorRef] ): Receive = LoggingReceive {
    case Started if sender().path == registerAggregatePath => {
      log debug "relay recd start confirmation from aggregate => activating"
      waiting foreach { _ ! Started }
      context become active
    }

    case WaitingForStart => {
      log debug s"adding to relay's wait queue: ${sender()}"
      context become starting( List( sender() ) )
    }
  }

  val active: Receive = LoggingReceive {
    case event if fullExtractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val directive = fullExtractor( event )
      proxy ! directive
      log debug s"relayed to aggregate register: ${directive}"
    }

    case WaitingForStart => {
      log debug s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }
  }

  override def unhandled( message: Any ): Unit = {
    log warning  s"RELAY_UNHANDLED ${message}; extractor-defined-at=${fullExtractor.isDefinedAt(message)}"
  }
}
