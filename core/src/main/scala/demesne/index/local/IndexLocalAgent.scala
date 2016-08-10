package demesne.index.local

import scala.concurrent.{ ExecutionContext, Future, ExecutionContextExecutor }
import scala.reflect.ClassTag
import scala.util.Try
import akka.actor.{ ActorRef, Actor, ActorLogging, Props }
import akka.agent.Agent
import akka.cluster.pubsub.DistributedPubSub
import akka.event.LoggingReceive
import demesne.AggregateRootType
import demesne.index._
import peds.commons.log.Trace
import peds.commons.util._


object IndexLocalAgent {
  def spec[K: ClassTag, I: ClassTag](
    specName: Symbol,
    specRelaySubscription: RelaySubscription = IndexBusSubscription
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

  def props[K: ClassTag, I: ClassTag]( topic: String ): Props = Props( new IndexLocalAgent[K, I]( topic ) )

  import scala.language.existentials

  type AkkaAgent[K, I] = Agent[Map[K, I]]

    /** Implements the Index trait through a locally cached Akka agent that is kept current with changes in the
      * index.
      */
  final class AgentIndex[K, I] private[local](
      agent: AkkaAgent[K, I]
  )(
    implicit override val ec: ExecutionContext
  ) extends Index[K, I] {
    override def get( key: K ): Option[I] = agent.get get key
    override def toString: String = getClass.safeSimpleName + s"( ${agent.get.mkString( "," )} )"
    override def futureGet( key: K ): Future[Option[I]] = agent.future() map { _ get key }

//      override def map[BK, BI]( f: (Entry) => (BK, BI) ): Index[BK, BI] = {
//        new AgentIndex[BK, BI]( Agent( agent().map(f) )( ec ) )
//      }
//
//      override def flatMap[BK, BI]( f: (Entry) => Index[BK, BI] ): Index[BK, BI] = {
//        val u: Map[K, I] = agent()
//        val z = f( u.head )
//        val t1 = for { ue <- u } yield {
//          val z: Int = f( ue )
//        }
////        val b:
//        val f: Index[BK, BI] = agent().flatMap(f)
//        f
//      }
//
//      override def foreach[U](f: ((K, I)) => U): Unit = ???
  }
}

/**
 * Created by damonrolfs on 10/27/14.
 */
class IndexLocalAgent[K: ClassTag, I: ClassTag](topic: String ) extends Actor with ActorLogging {
  import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
  import demesne.index.local.IndexLocalAgent._

  val trace = Trace( getClass.safeSimpleName, log )

  DistributedPubSub( context.system ).mediator ! Subscribe( topic, self ) // subscribe to the topic

  val dispatcher: ExecutionContextExecutor = trace.block( "dispatcher" ) {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.index-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  type IndexAgent = Agent[Map[K, I]]
  val index: IndexAgent = trace.block( "index" ) {Agent( Map[K, I]( ) )( dispatcher ) }

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
    case e @ IndexAggregate.Recorded( key: K, _, _, _ ) => trace.briefBlock( s"receive:RECORDED($key)" ) {
      val id = IndexAggregate.mapTo[I]( e.id ) // cast here to handled boxed primitive cases
      index send { r => r + ( key -> id ) }
    }

    case IndexAggregate.Withdrawn( identifier: I, _ ) => trace.briefBlock( s"receive:WITHDRAWN($identifier)" ) {
      index send { r =>
        val result = r collectFirst { case kv if kv._2 == identifier => r - kv._1 }
        result getOrElse r
      }
    }

    case IndexAggregate.Revised( oldKey: K, newKey: K, _ ) => trace.briefBlock( s"receive:REVISED($oldKey, $newKey)" ) {
      index send { r =>
        val result = r get oldKey map { id =>
          val rAdded = r + ( newKey -> id )
          rAdded - oldKey
        }

        result getOrElse r
      }
    }

    case GetIndex => sender( ) ! IndexEnvelope( new AgentIndex( index )( dispatcher ) )

    case WaitingForStart => {
      log debug s"recd WaitingForStart: sending Started to ${sender()}"
      sender() ! Started
    }
  }
}
