package demesne.register.local

import akka.actor.{Actor, ActorLogging, Props}
import akka.agent.Agent
import akka.contrib.pattern.DistributedPubSubExtension
import akka.event.LoggingReceive
import demesne.AggregateRootType
import demesne.register._
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag
import scala.util.Try


object RegisterLocalAccess {
  def spec[K: ClassTag, I: ClassTag](
    specName: Symbol,
    specRootType: AggregateRootType,
    specRelaySubscription: RelaySubscription = RegisterBusSubscription
  )(
    extractor: KeyIdExtractor[K, I]
  ): FinderSpec[K, I] = new FinderSpec[K, I] {
    override val name: Symbol = specName
    override val rootType: AggregateRootType = specRootType
    override def keyIdExtractor: KeyIdExtractor[K, I] = extractor
    override def accessProps: Props = props[K, I]( topic )
    override def relaySubscription: RelaySubscription = specRelaySubscription
    override def toString: String = s"RegisterLocalAccessFinderSpec($specName, $specRootType)"
  }

  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterLocalAccess[K, I]( topic ) )

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
class RegisterLocalAccess[K: ClassTag, I: ClassTag]( topic: String ) extends Actor with ActorLogging {
  import akka.contrib.pattern.DistributedPubSubMediator.{Subscribe, SubscribeAck}
  import demesne.register.local.RegisterLocalAccess._

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
  type RegisterAgent = RegisterLocalAccess.RegisterAgent[K, I]

  val register: RegisterAgent = trace.block( "register" ) { Agent( Map[K, I]() )( dispatcher ) }

  override def receive: Receive = LoggingReceive {
    case SubscribeAck( Subscribe(topic, None, `self`) ) => context become ready
  }

  val ready: Receive = LoggingReceive {
    case e @ RegisterAggregate.Recorded( key: K, _, _, _ ) => trace.block( s"receive:${e}" ) {
      val id = e.mapIdTo[I] // cast here to handled boxed primitive cases
      register send { r => r + ( key -> id ) }
    }

    case GetRegister => trace.block( "receive:GetRegister" ) { sender() ! RegisterEnvelope( new AgentRegister(register) ) }
  }
}
