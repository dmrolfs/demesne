package demesne

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import demesne.factory.ActorFactory
import peds.commons.identifier._
import peds.commons.log.Trace
import peds.commons.module.ModuleLifecycle


trait AggregateRootModule extends ModuleLifecycle

//DMR: use actorFactory? how specify? via root type? DRY?
trait AggregateRootModuleCompanion extends LazyLogging {
  def trace: Trace[_]

  type ID = ShortUUID
  type TID = TaggedID[ID]
  def nextId: TID = ShortUUID()
  def aggregateIdTag: Symbol
  def shardName: String = _shardName
  def aggregateRootType( implicit system: ActorSystem = this.system ): AggregateRootType

  def aggregateOf( id: TID )( implicit system: ActorSystem = this.system ): AggregateRootRef = aggregateOf( Some(id) )

  def aggregateOf( id: Option[TID] )( implicit system: ActorSystem ): AggregateRootRef = trace.block( "aggregateOf" ) {
    val effId = id getOrElse nextId
    model.aggregateOf( rootType = aggregateRootType, id = effId )
  }

  //DMR: although there is an assumption of each Aggregate existing in only one ActorSystem in a JVM,
  // these context properties must be def's rather than val's in order to support testing using multiple isolated
  // fixtures.
  //DMR: I don't like this def but need to determine how to supply system to aggregateRootType, esp in regular actors
  implicit def system: ActorSystem = {
    _context get demesne.SystemKey map { _.asInstanceOf[ActorSystem] } getOrElse ActorSystem()
  }

  def model: DomainModel = {
    _context get demesne.ModelKey map { _.asInstanceOf[DomainModel] } getOrElse DomainModel()
  }

  def factory: ActorFactory = {
    _context get demesne.FactoryKey map { _.asInstanceOf[ActorFactory] } getOrElse demesne.factory.systemFactory
  }

  private[this] var _context: Map[Symbol, Any] = _
  protected def context_=( c: Map[Symbol, Any] ): Unit = _context = c

  def context: Map[Symbol, Any] = {
    require( Option(_context).isDefined, "must start aggregate root module with context" )
    _context
  }

  def initialize( moduleContext: Map[Symbol, Any] ): Unit = {
    _context = moduleContext
    model.registerAggregateType( aggregateRootType, factory )
  }

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )
  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
}
