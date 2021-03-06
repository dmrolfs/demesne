package demesne.index.local

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future }
import scala.reflect.ClassTag
import scala.util.Try
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.agent.Agent
import akka.cluster.pubsub.DistributedPubSub
import akka.event.LoggingReceive
import demesne.AggregateRootType
import demesne.index.{ IndexAggregateProtocol => P, _ }
import omnibus.core.syntax.clazz._
import omnibus.identifier.{ Id, Identifying }

object IndexLocalAgent {

  def spec[K: ClassTag, I: ClassTag, V: ClassTag](
    specName: Symbol,
    specRelaySubscription: RelaySubscription = IndexBusSubscription
  )(
    extractor: KeyIdExtractor
  ): IndexSpecification = {
    new LocalIndexSpecification[K, I, V](
      name = specName,
      relaySubscription = specRelaySubscription,
      keyIdExtractor = extractor
    )
  }

  final class LocalIndexSpecification[K: ClassTag, I: ClassTag, V: ClassTag] private[IndexLocalAgent] (
    override val name: Symbol,
    override val relaySubscription: RelaySubscription,
    override val keyIdExtractor: KeyIdExtractor
  ) extends CommonIndexSpecification[K, I, V] {
    override def agentProps( rootType: AggregateRootType ): Props =
      IndexLocalAgent.props[K, I, V]( topic( rootType ) )
  }

  def props[K: ClassTag, I: ClassTag, V: ClassTag]( topic: String ): Props =
    Props( new IndexLocalAgent[K, I, V]( topic ) )

  import scala.language.existentials

  type AkkaAgent[K, I, V] = Agent[Map[K, IndexedValue[I, V]]]

  /** Implements the Index trait through a locally cached Akka agent that is kept current with changes in the
    * index.
    */
  final class AgentIndex[K, I, V] private[local] (
    agent: AkkaAgent[K, I, V]
  )(
    implicit override val ec: ExecutionContext
  ) extends Index[K, I, V] {

    /** Returns all of the current aggregate id key entries.
      *
      * @return a map containing the aggregate ids and associated keys.
      */
    override def indexedValueEntries: Map[K, IndexedValue[I, V]] = agent.get()
    override def futureIndexedValueEntries: Future[Map[K, IndexedValue[I, V]]] = agent.future()
    override def getIndexedValue( key: K ): Option[IndexedValue[I, V]] = agent.get get key
    override def toString: String = getClass.safeSimpleName + s"( ${agent.get.mkString( "," )} )"
    override def futureGetIndexedValue( key: K ): Future[Option[IndexedValue[I, V]]] =
      agent.future() map { _ get key }
  }
}

/**
  * Created by damonrolfs on 10/27/14.
  */
class IndexLocalAgent[K: ClassTag, I: ClassTag, V: ClassTag]( topic: String )
    extends Actor
    with ActorLogging {
  import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
  import demesne.index.local.IndexLocalAgent._

//  implicit val identifying = new Identifying[State] {
//    private val id = IndexIdentifier.make[K, I, V]( topic )
//    override type ID = IndexIdentifier
//    override val zeroValue: ID = id
//    override val nextValue: ID = id
//    override def valueFromRep( rep: String ): ID = id
//  }
  implicit val identifying = {
    val id = IndexIdentifier.make[K, I, V]( topic )

    Identifying.pure[State, IndexIdentifier](
      zeroValueFn = id,
      nextValueFn = () => id,
      valueFromRepFn = _ => id
    )
  }

  val tid: Id[State] = identifying.next
  val KeyType: ClassTag[K] = implicitly[ClassTag[K]]
  val IdType: ClassTag[I] = implicitly[ClassTag[I]]
  val ValueType: ClassTag[V] = implicitly[ClassTag[V]]

  DistributedPubSub( context.system ).mediator ! Subscribe( topic, self ) // subscribe to the topic

  val dispatcher: ExecutionContextExecutor = {
    val result = Try {
      context.system.dispatchers.lookup( "demesne.index-dispatcher" )
    } recover {
      case _ => scala.concurrent.ExecutionContext.global
    }
    result.get
  }

  type State = Map[K, IndexedValue[I, V]]
  type IndexAgent = Agent[State]
  val index: IndexAgent = Agent( Map.empty[K, IndexedValue[I, V]] )( dispatcher )

  override def receive: Receive = LoggingReceive { starting( List() ) }

  def starting( waiting: List[ActorRef] ): Receive = {
    case SubscribeAck( Subscribe( topic, None, `self` ) ) => {
      log.debug( "confirmed subscription to distributed PubSub topic:[{}] => activating", topic )
      waiting foreach { _ ! Started }
      context become LoggingReceive { ready }
    }

    case WaitingForStart => {
      log.debug( "adding actor to wait stack:[{}]", sender() )
      context become LoggingReceive { starting( sender() :: waiting ) }
    }
  }

  val ready: Receive = {
    case P.Recorded( _, KeyType( key ), IdType( id ), ValueType( value ) ) => {
      val iValue = IndexedValue[I, V]( id, value )
      index send { r =>
        r + (key -> iValue)
      }
    }

    case P.Withdrawn( _, Some( KeyType( key ) ), IdType( _ ) ) =>
      index alter { i =>
        i - key
      }

    case P.Withdrawn( _, None, IdType( id ) ) => {
      index send { r =>
        val result = r collectFirst { case ( k, IndexedValue( i, _ ) ) if i == id => r - k }
        result getOrElse r
      }
    }

    case P.KeyRevised( _, KeyType( oldKey ), KeyType( newKey ) ) => {
      index send { r =>
        val result = r.get( oldKey ) map { value =>
          val rAdded = r + (newKey -> value)
          rAdded - oldKey
        }

        result getOrElse r
      }
    }

    case P.ValueRevised( _, KeyType( key ), ValueType( _ ), ValueType( newValue ) ) => {
      index send { r =>
        val result = r.get( key ) map { iValue =>
          r + (key -> iValue.copy( value = newValue ))
        }
        result getOrElse r
      }
    }

    case GetIndex => sender() ! IndexEnvelope( new AgentIndex( index )( dispatcher ) )

    case WaitingForStart => {
      log.debug( "recd WaitForStart: sending Started to [{}]", sender() )
      sender() ! Started
    }
  }
}
