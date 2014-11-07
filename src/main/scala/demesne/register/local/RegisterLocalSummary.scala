package demesne.register.local

import akka.actor.{Actor, ActorLogging, Props}
import akka.agent.Agent
import akka.contrib.pattern.DistributedPubSubExtension
import akka.event.LoggingReceive
import demesne.register.{GetRegister, Register, RegisterAggregate, RegisterEnvelope}
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag
import scala.util.Try


object RegisterLocalSummary {
  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterLocalSummary[K, I]( topic ) )

  import scala.language.existentials
  type RegisterAgent[K, I] = Agent[RegisterAggregate.Register[K, I]]


    /** Implements the Register trait through a locally cached Akka agent that is kept current with changes in the
      * register.
      */
  class AgentRegister[K, I]( agent: RegisterAgent[K, I] ) extends Register[K, I] {
    override def get( key: K ): Option[I] = agent.get get key
    override def toString: String = getClass.safeSimpleName + s"( ${agent.get.mkString( "," )} )"
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterLocalSummary[K: ClassTag, I: ClassTag]( topic: String ) extends Actor with ActorLogging {
  import akka.contrib.pattern.DistributedPubSubMediator.{Subscribe, SubscribeAck}
  import demesne.register.local.RegisterLocalSummary._

  val trace = Trace( getClass.safeSimpleName, log )

  DistributedPubSubExtension( context.system ).mediator ! Subscribe( topic, self ) // subscribe to the topic

  val dispatcher: ExecutionContextExecutor = trace.block( "dispatcher" ) {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.register-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  type Register = RegisterAggregate.Register[K, I]
  type RegisterAgent = RegisterLocalSummary.RegisterAgent[K, I]

  val register: RegisterAgent = trace.block( "register" ) { Agent( Map[K, I]() )( dispatcher ) }

  override def receive: Receive = LoggingReceive {
    case SubscribeAck( Subscribe(topic, None, `self`) ) => context become ready
  }

  val ready: Receive = LoggingReceive {
    case e @ RegisterAggregate.AggregateRecorded( key: K, _, _, _ ) => trace.block( s"receive:${e}" ) {
      val id = e.mapIdTo[I] // cast here to handled boxed primitive cases
      register send { r => r + ( key -> id ) }
    }

    case GetRegister => trace.block( "receive:GetRegister" ) { sender() ! RegisterEnvelope( new AgentRegister(register) ) }
  }
}
