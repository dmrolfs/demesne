package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import peds.commons.TryV
import peds.commons.identifier.TaggedID
import peds.commons.log.Trace
import peds.commons.util._


abstract class AggregateRootModule extends AggregateRootType.Provider with LazyLogging { module =>
  def trace: Trace[_]

  type ID

  type TID = TaggedID[ID]
  def nextId: TryV[TID]

  def aggregateIdTag: Symbol = _aggregateIdTag
  def shardName: String = _shardName

  def aggregateOf( id: TID )( implicit model: DomainModel ): ActorRef = model( rootType = module.rootType, id )

  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )

  override def toString: String = s"${getClass.safeSimpleName}(${aggregateIdTag})"


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
