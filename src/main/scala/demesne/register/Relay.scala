package demesne.register

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.contrib.pattern.ReliableProxy
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._
import scala.reflect.ClassTag


object Relay extends com.typesafe.scalalogging.LazyLogging {
  type KeyIdExtractor[K, I] = PartialFunction[Any, (K, I)]
  def props[K, I]( register: ActorRef, keyIdExtractor: KeyIdExtractor[K, I] ): Props = {
    Props( new Relay( register, keyIdExtractor) )
  }

//  sealed trait Message
//  case class SubscribeTo( aggregate: ActorRef )
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class Relay[K, I]( register: ActorRef, extractor: Relay.KeyIdExtractor[K, I] )
extends Actor
with ActorLogging {

  val trace = Trace( getClass.safeSimpleName, log )

//  val classifier: Class[_] = implicitly[ClassTag[Msg]].runtimeClass
  val proxy = context.actorOf( ReliableProxy.props( targetPath = register.path, retryAfter = 100.millis ) )


//  @scala.throws[Exception](classOf[Exception])
//  override def preStart(): Unit = log info s"Msg type=${implicitly[ClassTag[Msg]].runtimeClass}"

  override def receive: Receive = LoggingReceive {
//    case SubscribeTo( aggregate ) => trace.block( s"receive:SubscribeTo(${aggregate})" ) { context.system.eventStream.subscribe( aggregate, classifier ) }
//
    case event if extractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val (key, id) = extractor( event )
      val registered = RegisterAggregate.RecordAggregate( key = key, id = id )
      proxy ! registered
      log info s"relayed to aggregate register: ${registered}"
    }

    case m => log info s"RELAY UNHANDLED ${m} class=${m.getClass}; extractor=${extractor.isDefinedAt(m)}"
  }
}
