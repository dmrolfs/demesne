package demesne.register.local

import akka.actor.{ActorRef, Actor, ActorLogging, Props}
import akka.agent.Agent
import akka.contrib.pattern.DistributedPubSubExtension
import akka.event.LoggingReceive
import demesne.AggregateRootType
import demesne.register._
import peds.commons.log.Trace
import peds.commons.util._

import scala.concurrent.{ExecutionContext, Future, ExecutionContextExecutor}
import scala.reflect.ClassTag
import scala.util.Try


object RegisterLocalAgent {
  def spec[K: ClassTag, I: ClassTag](
    specName: Symbol,
    specRelaySubscription: RelaySubscription = RegisterBusSubscription
  )(
    extractor: KeyIdExtractor[K, I]
  ): FinderSpec[K, I] = new FinderSpec[K, I] {
    override val name: Symbol = specName
    override def keyIdExtractor: KeyIdExtractor[K, I] = extractor
    override def agentProps( rootType: AggregateRootType ): Props = props[K, I]( topic(rootType) )
    override def relaySubscription: RelaySubscription = specRelaySubscription
  }

  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterLocalAgent[K, I]( topic ) )

  import scala.language.existentials

  type AkkaAgent[K, I] = Agent[Map[K, I]]

    /** Implements the Register trait through a locally cached Akka agent that is kept current with changes in the
      * register.
      */
  class AgentRegister[K, I](
      agent: AkkaAgent[K, I]
  )(
    implicit override val ec: ExecutionContext
  ) extends Register[K, I] {
    override def get( key: K ): Option[I] = agent.get get key
    override def toString: String = getClass.safeSimpleName + s"( ${agent.get.mkString( "," )} )"
    override def futureGet( key: K ): Future[Option[I]] = agent.future(). map { _ get key }

//      override def map[BK, BI]( f: (Entry) => (BK, BI) ): Register[BK, BI] = {
//        new AgentRegister[BK, BI]( Agent( agent().map(f) )( ec ) )
//      }
//
//      override def flatMap[BK, BI]( f: (Entry) => Register[BK, BI] ): Register[BK, BI] = {
//        val u: Map[K, I] = agent()
//        val z = f( u.head )
//        val t1 = for { ue <- u } yield {
//          val z: Int = f( ue )
//        }
////        val b:
//        val f: Register[BK, BI] = agent().flatMap(f)
//        f
//      }
//
//      override def foreach[U](f: ((K, I)) => U): Unit = ???
    }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class RegisterLocalAgent[K: ClassTag, I: ClassTag]( topic: String ) extends Actor with ActorLogging {
  import akka.contrib.pattern.DistributedPubSubMediator.{Subscribe, SubscribeAck}
  import demesne.register.local.RegisterLocalAgent._

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

  type RAgent = Agent[Map[K, I]]
  val register: RAgent = trace.block( "register" ) { Agent( Map[K, I]() )( dispatcher ) }

  override def receive: Receive = starting( List() )

  def starting( waiting: List[ActorRef] ): Receive = LoggingReceive {
    case SubscribeAck( Subscribe(topic, None, `self`) ) => {
      log debug s"confirmed subscription to distributed PubSub topic=$topic => activating"
      waiting foreach { _ ! Started }
      context become ready
    }

    case WaitingForStart => {
      log debug s"adding actor to wait stack: ${sender()}"
      context become starting( sender() :: waiting )
    }
  }

  val ready: Receive = LoggingReceive {
    case e @ RegisterAggregate.Recorded( key: K, _, _, _ ) => trace.block( s"receive:${e}" ) {
      val id = e.mapIdTo[I] // cast here to handled boxed primitive cases
      register send { r => r + ( key -> id ) }
    }

    case GetRegister => sender() ! RegisterEnvelope( new AgentRegister( register )( dispatcher ) )

    case WaitingForStart => {
      log debug s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }
  }
}
