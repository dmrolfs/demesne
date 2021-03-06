package demesne.index

import scala.concurrent.duration._
import akka.actor.FSM.{ CurrentState, Transition }
import akka.actor._
import akka.contrib.pattern.ReliableProxy
import akka.contrib.pattern.ReliableProxy.{ Connecting, TargetChanged }
import akka.event.LoggingReceive
import omnibus.akka.envelope.Envelope

object IndexRelay {

  def props( indexAggregatePath: ActorPath, extractor: KeyIdExtractor ): Props = {
    Props( new IndexRelay( indexAggregatePath, extractor ) )
  }
}

/**
  * Created by damonrolfs on 10/27/14.
  */
class IndexRelay( indexAggregatePath: ActorPath, extractor: KeyIdExtractor )
    extends Actor
    with ActorLogging {

  val fullExtractor: KeyIdExtractor = {
    case m if extractor.isDefinedAt( m )                            => extractor( m )
    case Envelope( payload, _ ) if extractor.isDefinedAt( payload ) => extractor( payload )
  }

  //todo move into configuration retry timeout
  val proxy: ActorRef = context.actorOf(
    ReliableProxy.props( targetPath = indexAggregatePath, retryAfter = 100.millis )
  )
  proxy ! FSM.SubscribeTransitionCallBack( self )

  //todo: move into configuration
  override val receive: Receive = connecting( List() )

  def connecting( waiting: List[ActorRef] ): Receive = LoggingReceive {
    case CurrentState( _, state ) if state != Connecting => {
      log.debug( "Relay connected to index aggregate at {}", indexAggregatePath )
      proxy ! WaitingForStart
      context become starting( waiting )
    }

    case Transition( _, Connecting, _ ) => {
      log.debug( "Relay connected to index aggregate at {}", indexAggregatePath )
      proxy ! WaitingForStart
      context become starting( waiting )
    }

    case WaitingForStart => {
      log.debug( "adding to relay's wait queue: {}", sender() )
      context become connecting( List( sender() ) )
    }

    case _: CurrentState[_] => {}
    case _: ActorIdentity   => {}
    case _: TargetChanged   => {}
  }

  def starting( waiting: List[ActorRef] ): Receive = LoggingReceive {
    case Started if sender().path == indexAggregatePath => {
      log.debug( "relay recd start confirmation from aggregate => activating" )
      waiting foreach { _ ! Started }
      context become active
    }

    case WaitingForStart => {
      log.debug( "adding to relay's wait queue: {}", sender() )
      context become starting( List( sender() ) )
    }

    case _: Transition[_]   => {}
    case _: CurrentState[_] => {}
    case _: ActorIdentity   => {}
    case _: TargetChanged   => {}
  }

  val active: Receive = LoggingReceive {
    case event if fullExtractor.isDefinedAt( event ) => {
      val directive = fullExtractor( event )
      proxy ! directive
      log.debug( "relayed to aggregate index: {}", directive )
    }

    case WaitingForStart => {
      log.debug( "received WaitForStart: sending Started to {}", sender() )
      sender() ! Started
    }

    case _: Transition[_]   => {}
    case _: CurrentState[_] => {}
    case _: ActorIdentity   => {}
    case _: TargetChanged   => {}
  }

  override def unhandled( message: Any ): Unit = {
    message match {
//      case _: akka.actor.FSM.CurrentState[_] => ()
//      case _: akka.actor.FSM.Transition[_] => ()
//      case _: akka.contrib.pattern.ReliableProxy.TargetChanged => ()
//      case id: ActorIdentity => log.debug( "received ActorIdentity:[{}]", id )
      case _ =>
        log.warning(
          "RELAY_UNHANDLED [{}]; extractor-defined-at={}",
          message,
          fullExtractor.isDefinedAt( message )
        )
    }
  }
}
