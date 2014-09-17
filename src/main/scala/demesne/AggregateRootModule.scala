package demesne

import akka.actor.{ ActorRef, ActorSystem }
import akka.contrib.pattern.ClusterSharding
import com.typesafe.scalalogging.LazyLogging
import peds.commons.identifier._
import peds.commons.module.ModuleLifecycle


trait AggregateRootModule extends ModuleLifecycle {
  protected def startClusterShard( rootType: AggregateRootType )( implicit system: ActorSystem ): ActorRef = {
    val repoSpec = EnvelopingAggregateRootRepository specificationFor rootType
    ClusterSharding( system ).start(
      typeName = repoSpec.name,
      entryProps = Some( repoSpec.props ),
      idExtractor = repoSpec.idExtractor,
      shardResolver = repoSpec.shardResolver
    )

    ClusterSharding( system ).start(
      typeName = rootType.name,
      entryProps = Some( rootType.aggregateRootProps ),
      idExtractor = rootType.aggregateIdFor,
      shardResolver = rootType.shardIdFor
    )
  }
}


//DMR: use actorFactory? how specify? via root type? DRY?
trait AggregateRootModuleCompanion extends LazyLogging {
  // val trace: Trace[_]
  type ID = ShortUUID
  type TID = TaggedID[ID]
  def nextId: TID = ShortUUID()
  def aggregateIdTag: Symbol
  def shardName: String = _shardName
  def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType

  def aggregateOf( id: Option[TID] )( implicit system: ActorSystem = this.system ): AggregateRootRef = {
    val effId = id getOrElse nextId
    model.aggregateOf( rootType = aggregateRootType, id = effId )
  }

  //DMR: I don't like this var but need to determine how to supply system to aggregateRootType, esp in regular actors
  val SystemKey: Symbol = 'system
  implicit lazy val system: ActorSystem = _context get SystemKey map { _.asInstanceOf[ActorSystem] } getOrElse ActorSystem()
  // def system: ActorSystem = {
  //   require( Option(_system).isDefined, s"${getClass.getName} must be start with context supplying 'system" )
  //   _system
  // }

  implicit lazy val model: DomainModel = _context get 'model map { _.asInstanceOf[DomainModel] } getOrElse DomainModel()

  private[this] var _context: Map[Symbol, Any] = _
  protected def context_=( c: Map[Symbol, Any] ): Unit = _context = c

  def context: Map[Symbol, Any] = {
    require( Option(_context).isDefined, "must start aggregate root module with context" )
    _context
  }

  def initialize( moduleContext: Map[Symbol, Any] ): Unit = _context = moduleContext

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )
  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
}
