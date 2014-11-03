package demesne.register

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.contrib.pattern.ReliableProxy
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.duration._
import scala.reflect.ClassTag


object Relay extends com.typesafe.scalalogging.LazyLogging {
//  type Msg = Any
//  type Key = Any
//  type Id = Any
  type KeyIdExtractor[Msg, Key, Id] = PartialFunction[Msg, (Key, Id)]
  def props[Msg: ClassTag, Key, Id]( register: ActorRef, keyIdExtractor: KeyIdExtractor[Msg, Key, Id] ): Props = {
    Trace( "Relay", logger ).msg( s"props:: Msg=${implicitly[ClassTag[Msg]].runtimeClass}" )
    Props( new Relay( register, keyIdExtractor) )
  }

//  sealed trait Message
//  case class SubscribeTo( aggregate: ActorRef )
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class Relay[Msg: ClassTag, Key, Id]( register: ActorRef, keyIdExtractor: PartialFunction[Msg, (Key, Id)] )
extends Actor
with ActorLogging {

  val trace = Trace( getClass.safeSimpleName, log )

  val classifier: Class[_] = implicitly[ClassTag[Msg]].runtimeClass
  val proxy = context.actorOf( ReliableProxy.props( targetPath = register.path, retryAfter = 100.millis ) )


  @scala.throws[Exception](classOf[Exception])
  override def preStart(): Unit = log info s"Msg type=${implicitly[ClassTag[Msg]].runtimeClass}"

  override def receive: Receive = LoggingReceive {
//    case SubscribeTo( aggregate ) => trace.block( s"receive:SubscribeTo(${aggregate})" ) { context.system.eventStream.subscribe( aggregate, classifier ) }
//
    case event: Msg if keyIdExtractor.isDefinedAt( event ) => trace.block( s"receive:${event}" ) {
      val (key, id) = keyIdExtractor( event )
      val registered = Register.RegisterAggregate( key = key, id = id, classifier = classifier )
      proxy ! registered
      log info s"relayed to aggregate register: ${registered}"
    }

    case m => log info s"RELAY UNHANDLED ${m} class=${m.getClass}; classifier[${classifier}}]=${classifier.isInstance(m)}; implicitly=${implicitly[ClassTag[Msg]].runtimeClass}; extractor=${keyIdExtractor.isDefinedAt(m.asInstanceOf[Msg])}"
  }
}
