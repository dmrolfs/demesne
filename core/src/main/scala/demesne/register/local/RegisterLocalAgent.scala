package demesne.register.local

import scala.concurrent.{ ExecutionContext, Future, ExecutionContextExecutor }
import scala.reflect.ClassTag
import scala.util.Try
import akka.actor.{ ActorRef, Actor, ActorLogging, Props }
import akka.agent.Agent
import akka.cluster.pubsub.DistributedPubSub
import akka.event.LoggingReceive
import demesne.AggregateRootType
import demesne.register._
import peds.commons.log.Trace
import peds.commons.util._


object RegisterLocalAgent {
  def spec[K: ClassTag, I: ClassTag](
    specName: Symbol,
    specRelaySubscription: RelaySubscription = RegisterBusSubscription
  )(
    extractor: KeyIdExtractor
  ): AggregateIndexSpec[K, I] = {
    new AggregateIndexSpec[K, I] {
      override val name: Symbol = specName
      override def keyIdExtractor: KeyIdExtractor = extractor
      override def agentProps( rootType: AggregateRootType ): Props = props[K, I]( topic(rootType) )( keyTag, idTag )
      override def relaySubscription: RelaySubscription = specRelaySubscription
    }
  }

  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new RegisterLocalAgent[K, I]( topic ) )

  import scala.language.existentials

  type AkkaAgent[K, I] = Agent[Map[K, I]]

    /** Implements the Register trait through a locally cached Akka agent that is kept current with changes in the
      * register.
      */
  final class AgentRegister[K, I] private[local](
      agent: AkkaAgent[K, I]
  )(
    implicit override val ec: ExecutionContext
  ) extends Register[K, I] {
    override def get( key: K ): Option[I] = agent.get get key
    override def toString: String = getClass.safeSimpleName + s"( ${agent.get.mkString( "," )} )"
    override def futureGet( key: K ): Future[Option[I]] = agent.future() map { _ get key }

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
  import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
  import demesne.register.local.RegisterLocalAgent._

  val trace = Trace( getClass.safeSimpleName, log )

  DistributedPubSub( context.system ).mediator ! Subscribe( topic, self ) // subscribe to the topic

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
    case e @ RegisterAggregate.Recorded( key: K, _, _, _ ) => trace.briefBlock( s"receive:RECORDED($key)" ) {
      val id = e.mapIdTo[I] // cast here to handled boxed primitive cases
      register send { r => r + ( key -> id ) }
    }

    case RegisterAggregate.Withdrawn( identifier: I, _ ) => trace.briefBlock( s"receive:WITHDRAWN($identifier)") { 
      register send { r => 
        val result = r collectFirst { case kv if kv._2 == identifier => r - kv._1 }
        result getOrElse r
      } 
    }

    case RegisterAggregate.Revised( oldKey: K, newKey: K, _ ) => trace.briefBlock( s"receive:REVISED($oldKey, $newKey)" ) {
      register send { r => 
        val result = r get oldKey map { id =>
          val rAdded = r + ( newKey -> id )
          rAdded - oldKey
        }

        result getOrElse r
      }
    }

    case GetRegister => sender() ! RegisterEnvelope( new AgentRegister( register )( dispatcher ) )

    case WaitingForStart => {
      log debug s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }
  }
}
