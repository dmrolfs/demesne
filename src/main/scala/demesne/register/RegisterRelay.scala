package demesne.register

import akka.actor._
import akka.contrib.pattern.ReliableProxy
import akka.event.LoggingReceive
import demesne.register.RegisterRelay.Tick
import peds.akka.envelope.Envelope
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._


object RegisterRelay extends com.typesafe.scalalogging.LazyLogging {
  def props[K, I]( registerAggregatePath: ActorPath, extractor: KeyIdExtractor[K, I] ): Props = {
    Props( new RegisterRelay( registerAggregatePath, extractor) )
  }

  private case object Tick
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterRelay[K, I]( registerAggregatePath: ActorPath, extractor: KeyIdExtractor[K, I] )
extends Actor
with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  val fullExtractor: KeyIdExtractor[K, I] = {
    case m if extractor.isDefinedAt( m ) => extractor( m )
    case e @ Envelope( payload, _ ) if extractor.isDefinedAt( payload ) => extractor( payload )
  }

  //todo move into configuration retry timeout
  val proxy: ActorRef = context.actorOf(
    ReliableProxy.props( targetPath = registerAggregatePath, retryAfter = 100.millis )
  )

  proxy ! WaitingForStart

  //todo: move into configuration
  import context.dispatcher
  val tickTask = context.system.scheduler.schedule(
    initialDelay = 500.millis,
    interval = 200.millis,
    receiver = self,
    message = Tick
  )

  override def postStop(): Unit = {
    tickTask.cancel()
    super.postStop()
  }

  //todo: move into configuration
  override val receive: Receive = starting( List(), 10 )

  def starting( waiting: List[ActorRef], retries: Int ): Receive = LoggingReceive {
    case Started if sender().path == registerAggregatePath => {
      log info "relay recd start confirmation from aggregate => activating"
      waiting foreach { _ ! Started }
      context become active
    }

    case WaitingForStart => {
      log info s"adding to relay's wait queue: ${sender()}"
      context become starting( List( sender() ), retries )
    }

    case Tick => {
      val retriesLeft = retries - 1
      log info s"waiting on register aggregate; retries left:${retriesLeft}"
      context become starting( waiting, retriesLeft )
    }
  }

  val active: Receive = LoggingReceive {
    case event if fullExtractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val (key, id) = fullExtractor( event )
      val recordAggregate = RegisterAggregate.Record( key = key, id = id )
      proxy ! recordAggregate
      log info s"relayed to aggregate register: ${recordAggregate}"
    }

    case WaitingForStart => {
      log info s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }
  }

  override def unhandled( message: Any ): Unit = {
    log warning  s"RELAY_UNHANDLED ${message}; extractor-defined-at=${fullExtractor.isDefinedAt(message)}"
  }
}
