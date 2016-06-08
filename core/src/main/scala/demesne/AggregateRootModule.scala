package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import peds.archetype.domain.model.core.Identifying
import peds.commons.TryV
import peds.commons.identifier.TaggedID
import peds.commons.log.Trace
import peds.commons.util._


abstract class AggregateRootModule[I: Identifying]
extends AggregateRootType.Provider
with CommonInitializeAggregateActorType
with LazyLogging { module =>
  def trace: Trace[_]

  type ID = I
  type TID = TaggedID[ID]
  def nextId: TryV[TID] = implicitly[Identifying[ID]].nextId map { tagId }
  def aggregateIdTag: Symbol = _aggregateIdTag
  def shardName: String = _shardName

  def aggregateOf( id: TID )( implicit model: DomainModel ): ActorRef = model.aggregateOf( rootType = module.rootType, id )

//  def aggregateOf( id: Option[TID] )( implicit model: DomainModel ): ActorRef = trace.block( s"aggregateOf($id)($model)" ) {
//    val effId = id getOrElse nextId
//    model.aggregateOf( rootType = module.rootType, id = effId )
//  }
//
//  def aggregateOf( id: TryV[TID] )( implicit model: DomainModel ): ActorRef

  override def toString: String = s"${getClass.safeSimpleName}(${aggregateIdTag})"

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )


  type Command = AggregateRootModule.Command[ID]
  type Event = AggregateRootModule.Event[ID]


  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
  private[this] lazy val _aggregateIdTag: Symbol = AggregateRootModule tagify getClass()
}

object AggregateRootModule {
  trait Command[I] extends CommandLike {
    override type ID = I
  }


  trait Event[I] extends EventLike {
    override type ID = I
  }


  val Module = """(\w+)Module""".r
  val Actor = """(\w+)Actor""".r
  val PersistentActor = """(\w+)PersistentActor""".r

  def tagify( clazz: Class[_] ): Symbol = {
    val name = clazz.safeSimpleName match {
      case Module(n) => n
      case Actor(n) => n
      case PersistentActor(n) => n
      case n => n
    }

    Symbol( name(0).toLower + name.drop(1) )
  } 
}
