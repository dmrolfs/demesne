package demesne.register

import akka.actor.{ActorLogging, Actor, ActorRef, Props}
import akka.contrib.pattern.ReliableProxy
import demesne.register.Register.AggregateRegistered

import scala.concurrent.duration._
import scala.reflect.ClassTag


object Relay {
  type Msg = Any
  type Key = Any
  type Id = Any
  type KeyIdExtractor = PartialFunction[Msg, (Key, Id)]
  def props( register: ActorRef, keyIdExtractor: KeyIdExtractor ): Props = {
    Props( new Relay( register, keyIdExtractor) )
  }

  sealed trait Message
  case class SubscribeTo( aggregate: ActorRef )
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class Relay[M: ClassTag]( register: ActorRef, keyIdExtractor: PartialFunction[M, (Any, Any)] )
extends Actor
with ActorLogging {
  import demesne.register.Relay._

  val classifier: Class[_] = implicitly[ClassTag[M]].runtimeClass
  val proxy = context.actorOf( ReliableProxy.props( targetPath = register.path, retryAfter = 100.millis ) )

  override def receive: Receive = {
    case SubscribeTo( aggregate ) => context.system.eventStream.subscribe( aggregate, classifier )

    case event: M if keyIdExtractor isDefinedAt event => {
      val (key, id) = keyIdExtractor( event )
      val registered = AggregateRegistered( key = key, id = id, classifier = classifier )
      proxy ! registered
      log info s"recorded in aggregate register: ${registered}"
    }
  }
}
