package demesne.register

import akka.actor.{Actor, ActorLogging, Props}
import akka.agent.Agent
import akka.event.LoggingReceive
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag
import scala.util.Try


object RegisterSubscriber {
  def props[Key, AggregateId: ClassTag]: Props = Props( new RegisterSubscriber[Key, AggregateId] )

  sealed trait Message

  case object GetRegister extends Message

  import scala.language.existentials
  case class Register( agent: Agent[_] ) {
    def mapTo[Key, AggregateId]: Agent[Map[Key, AggregateId]] = agent.asInstanceOf[Agent[Map[Key, AggregateId]]]
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterSubscriber[Key, AggregateId: ClassTag] extends Actor with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  type RegisterSummary = Map[Key, AggregateId]
  val dispatcher: ExecutionContextExecutor = trace.block( "dispatcher" ) {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.register-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  val register: Agent[RegisterSummary] = trace.block( "register" ) { Agent( Map[Key, AggregateId]() )( dispatcher ) }

  override def receive: Receive = LoggingReceive {
    case e: Register.AggregateRegistered => trace.block( s"receive:${e}" ) {
      val key = e.key.asInstanceOf[Key]
      val id = e.mapIdTo[AggregateId]
      register send { r => r + ( key -> id ) }
    }

    case RegisterSubscriber.GetRegister => trace.block( "receive:GetRegister" ) { sender() ! RegisterSubscriber.Register( register ) }
  }
}
