package demesne

import akka.actor.{ActorRef, ActorSystem}
import akka.contrib.pattern.ClusterSharding
import com.typesafe.scalalogging.LazyLogging
import demesne.factory.ActorFactory
import peds.commons.identifier._
import peds.commons.log.Trace
import peds.commons.module.ModuleLifecycle

import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._


trait AggregateModuleInitializationExtension {
  def initialize( rootType: AggregateRootType )( implicit model: DomainModel ): Unit = { }
}

trait ClusteredAggregateModuleExtension extends AggregateModuleInitializationExtension {
  override def initialize( rootType: AggregateRootType )( implicit model: DomainModel ): Unit = {
    ClusterSharding( model.system ).start(
      typeName = rootType.name,
      entryProps = Some( rootType.aggregateRootProps ),
      idExtractor = rootType.aggregateIdFor,
      shardResolver = rootType.shardIdFor
    )
  }
}


trait AggregateRootModule extends ModuleLifecycle { module: AggregateModuleInitializationExtension => }


trait AggregateRootModuleCompanion extends LazyLogging {
  def trace: Trace[_]

  type ID = ShortUUID
  type TID = TaggedID[ID]
  def nextId: TID = ShortUUID()
  def aggregateIdTag: Symbol
  def shardName: String = _shardName
  def aggregateRootType: AggregateRootType

  def aggregateOf( id: TID )( implicit model: DomainModel ): ActorRef = aggregateOf( Some(id) )

  def aggregateOf( id: Option[TID] )( implicit model: DomainModel ): ActorRef = trace.block( s"aggregateOf($id)($model)" ) {
    val effId = id getOrElse nextId
    model.aggregateOf( rootType = aggregateRootType, id = effId )
  }

  def initialize( module: AggregateModuleInitializationExtension, context: Map[Symbol, Any] ): Unit = trace.block( "initialize" ) {
    trace( s"context = $context" )
    require( context.contains( demesne.SystemKey ), "must initialize ${getClass.safeSimpleName} with ActorSystem" )
    require( context.contains( demesne.ModelKey ), "must initialize ${getClass.safeSimpleName} with DomainModel" )

    val s = context( demesne.SystemKey ).asInstanceOf[ActorSystem]
    implicit val m = context( demesne.ModelKey ).asInstanceOf[DomainModel]
    val f = context get demesne.FactoryKey map { _.asInstanceOf[ActorFactory] } getOrElse demesne.factory.systemFactory

    trace( s"system = $s" )
    trace( s"model = $m" )
    val rootType = aggregateRootType
    module.initialize( rootType )
    val reg = m.registerAggregateType( rootType, f )
    Await.result( reg, 10.seconds ) // todo push into configuration
  }

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )
  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
}
