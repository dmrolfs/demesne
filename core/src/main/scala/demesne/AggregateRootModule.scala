package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import omnibus.commons.TryV
import omnibus.commons.identifier.{Identifying2, TaggedID}
import omnibus.commons.util._


abstract class AggregateRootModule extends AggregateRootType.Provider with LazyLogging { module =>
  type ID
  val identifying: Identifying2.Aux[_, ID]
  type TID = TaggedID[ID]

  def nextId: TryV[TID] = identifying.nextTID

//  def aggregateIdTag: Symbol = identifying.idTag
  def shardName: String = _shardName

  def aggregateOf( id: Any  )( implicit model: DomainModel ): ActorRef = model( rootType = module.rootType, id )

  implicit def tagId( id: ID ): TID = identifying tag id
//  implicit def tagId( id: ID ): TID = TaggedID( aggregateIdTag, id )

  override def toString: String = s"${getClass.safeSimpleName}(${identifying.idTag.name})"


  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( identifying.idTag.name ).capitalize
//  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( aggregateIdTag.name ).capitalize
//  private[this] lazy val _aggregateIdTag: Symbol = identifying.idTag  //option with Aux IDentifying
//  private[this] lazy val _aggregateIdTag: Symbol = AggregateRootModule tagify getClass() // legacy option
}

object AggregateRootModule {

  trait Message[I] extends MessageLike {
    override type ID = I
  }

  trait Command[I] extends Message[I] with CommandLike 


  trait Event[I] extends EventLike {
    override type ID = I
  }


//  val Module = """(\w+)Module""".r
//  val Actor = """(\w+)Actor""".r
//  val PersistentActor = """(\w+)PersistentActor""".r
//
//  def tagify( clazz: Class[_] ): Symbol = {
//    val tag = clazz.safeSimpleName match {
//      case Module(n) => n
//      case Actor(n) => n
//      case PersistentActor(n) => n
//      case n => n
//    }
//
////    Symbol( tag(0).toLower + tag.drop(1) )
//    Symbol( tag )
//  }
}
