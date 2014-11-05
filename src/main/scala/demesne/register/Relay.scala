package demesne.register

import akka.actor._
import akka.contrib.pattern.ReliableProxy
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._


object Relay extends com.typesafe.scalalogging.LazyLogging {
  type KeyIdExtractor[K, I] = PartialFunction[Any, (K, I)]
  def props[K, I]( registerPath: ActorPath, extractor: KeyIdExtractor[K, I] ): Props = {
    Props( new Relay( registerPath, extractor) )
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class Relay[K, I]( registerPath: ActorPath, extractor: Relay.KeyIdExtractor[K, I] )
extends Actor
with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  //todo move into configuration retry timeout
  val proxy: ActorRef = context.actorOf( ReliableProxy.props( targetPath = registerPath, retryAfter = 100.millis ) )

  override val receive: Receive = LoggingReceive {
    case event if extractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val (key, id) = extractor( event )
      val registered = RegisterAggregate.RecordAggregate( key = key, id = id )
      proxy ! registered
      log info s"relayed to aggregate register: ${registered}"
    }
  }

  override def unhandled( message: Any ): Unit = {
    log warning  s"RELAYUNHANDLED ${message}; extractor-defined-at=${extractor.isDefinedAt(message)}"
  }
}
