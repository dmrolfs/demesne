package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import peds.commons.identifier._
import peds.commons.log.Trace


// CHANGE MODULE ref to ACTOR or PersistentActor

// trait AggregateRootModule extends ModuleLifecycle { module: AggregateModuleInitializationExtension => }
// trait AggregateRootModule { module => }


trait AggregateRootModule extends CommonInitializeAggregateActorType with LazyLogging {
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

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )
  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
}
