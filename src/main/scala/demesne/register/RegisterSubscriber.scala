package demesne.register

import akka.actor.{Actor, Props}
import akka.agent.Agent

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
class RegisterSubscriber[Key, AggregateId: ClassTag] extends Actor {
  type RegisterSummary = Map[Key, AggregateId]
  val dispatcher: ExecutionContextExecutor = {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.register-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  val register: Agent[RegisterSummary] = Agent( Map[Key, AggregateId]() )( dispatcher )

  override def receive: Receive = {
    case e: Register.AggregateRegistered => {
      val key = e.key.asInstanceOf[Key]
      val id = e.mapIdTo[AggregateId]
      register send { r => r + ( key -> id ) }
    }

    case RegisterSubscriber.GetRegister => sender() ! RegisterSubscriber.Register( register )
  }
}
