package demesne

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import demesne.factory.ActorFactory
import peds.commons.identifier._
import peds.commons.module.ModuleLifecycle


trait AggregateRootModule extends ModuleLifecycle

//DMR: use actorFactory? how specify? via root type? DRY?
trait AggregateRootModuleCompanion extends LazyLogging {
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
  implicit lazy val system: ActorSystem = {
    _context get demesne.SystemKey map { _.asInstanceOf[ActorSystem] } getOrElse ActorSystem()
  }

  implicit lazy val model: DomainModel = {
    _context get demesne.ModelKey map { _.asInstanceOf[DomainModel] } getOrElse DomainModel()
  }

  lazy val factory: ActorFactory = {
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
