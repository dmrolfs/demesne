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
  def props[K: ClassTag, I: ClassTag]: Props = Props( new RegisterSubscriber[K, I] )

  sealed trait Message

  case object GetRegister extends Message

  import scala.language.existentials
  type RegisterAgent[K, I] = Agent[RegisterAggregate.Register[K, I]]

  case class Register( agent: Agent[_] ) {
    def mapTo[K, I]: RegisterAgent[K, I] = agent.asInstanceOf[RegisterAgent[K, I]]
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterSubscriber[K: ClassTag, I: ClassTag] extends Actor with ActorLogging {
  val trace = Trace( getClass.safeSimpleName, log )

  type Register = RegisterAggregate.Register[K, I]
  type RegisterAgent = RegisterSubscriber.RegisterAgent[K, I]

  val dispatcher: ExecutionContextExecutor = trace.block( "dispatcher" ) {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.register-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  val register: RegisterAgent = trace.block( "register" ) { Agent( Map[K, I]() )( dispatcher ) }

  override def receive: Receive = LoggingReceive {
    case e @ RegisterAggregate.AggregateRecorded( key: K, _ ) => trace.block( s"receive:${e}" ) {
      val id = e.mapIdTo[I] //dmr: cast here to handled boxed primitive cases
      register send { r => r + ( key -> id ) }
    }

    case RegisterSubscriber.GetRegister => trace.block( "receive:GetRegister" ) { sender() ! RegisterSubscriber.Register( register ) }
  }
}
