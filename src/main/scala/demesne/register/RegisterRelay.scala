package demesne.register

import akka.actor._
import akka.contrib.pattern.ReliableProxy
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._


object RegisterRelay extends com.typesafe.scalalogging.LazyLogging {
  def props[K, I]( registerAggregatePath: ActorPath, extractor: KeyIdExtractor[K, I] ): Props = {
    Props( new RegisterRelay( registerAggregatePath, extractor) )
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterRelay[K, I]( registerAggregatePath: ActorPath, extractor: KeyIdExtractor[K, I] )
extends Actor
with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  //todo move into configuration retry timeout
  val proxy: ActorRef = context.actorOf(
    ReliableProxy.props( targetPath = registerAggregatePath, retryAfter = 100.millis )
  )

  override val receive: Receive = LoggingReceive {
    case event if extractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val (key, id) = extractor( event )
      val recordAggregate = RegisterAggregate.Record( key = key, id = id )
      proxy ! recordAggregate
      log info s"relayed to aggregate register: ${recordAggregate}"
    }
  }

  override def unhandled( message: Any ): Unit = {
    log warning  s"RELAYUNHANDLED ${message}; extractor-defined-at=${extractor.isDefinedAt(message)}"
  }
}
