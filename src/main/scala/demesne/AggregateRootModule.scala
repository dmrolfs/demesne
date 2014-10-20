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
  def aggregateRootType: AggregateRootType

  def aggregateOf( id: TID )( implicit model: DomainModel ): AggregateRootRef = aggregateOf( Some(id) )

  def aggregateOf( id: Option[TID] )( implicit model: DomainModel ): AggregateRootRef = trace.block( s"aggregateOf($id)($model)" ) {
    val effId = id getOrElse nextId
    model.aggregateOf( rootType = aggregateRootType, id = effId )
  }

  private[this] var _modelName: String = _

  def initialize( context: Map[Symbol, Any] ): Unit = trace.block( "initialize" ) {
    trace( s"context = $context" )
    require( context.contains( demesne.SystemKey ), "must initialize ${getClass.safeSimpleName} with ActorSystem" )
    require( context.contains( demesne.ModelKey ), "must initialize ${getClass.safeSimpleName} with DomainModel" )

    val s = context( demesne.SystemKey ).asInstanceOf[ActorSystem]
    val m = context( demesne.ModelKey ).asInstanceOf[DomainModel]
    val f = context get demesne.FactoryKey map { _.asInstanceOf[ActorFactory] } getOrElse demesne.factory.systemFactory

    _modelName = m.name

    trace( s"system = $s" )
    trace( s"model = $m" )
    m.registerAggregateType( aggregateRootType, f )
  }

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )
  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
}
