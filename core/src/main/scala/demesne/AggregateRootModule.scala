package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import peds.commons.identifier._
import peds.commons.log.Trace
import peds.commons.util._


trait AggregateRootModule extends CommonInitializeAggregateActorType with LazyLogging { module =>
  def trace: Trace[_]

  type ID = ShortUUID
  type TID = TaggedID[ID]
  def nextId: TID = ShortUUID()
  def aggregateIdTag: Symbol = _aggregateIdTag
  def shardName: String = _shardName
  def aggregateRootType: AggregateRootType

  def aggregateOf( id: TID )( implicit model: DomainModel ): ActorRef = aggregateOf( Some(id) )

  def aggregateOf( id: Option[TID] )( implicit model: DomainModel ): ActorRef = trace.block( s"aggregateOf($id)($model)" ) {
    val effId = id getOrElse nextId
    model.aggregateOf( rootType = aggregateRootType, id = effId )
  }

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

  private def tagify( clazz: Class[_] ): Symbol = {
    val name = clazz.safeSimpleName match {
      case Module(n) => n
      case Actor(n) => n
      case PersistentActor(n) => n
      case n => n
    }

    Symbol( name(0).toLower + name.drop(1) )
  } 
}
