package demesne

import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import omnibus.commons.ErrorOr
import omnibus.commons.identifier.{Identifying, TaggedID}
import omnibus.commons.util._


abstract class AggregateRootModule[S, I]( implicit val identifying: Identifying.Aux[S, I] )
  extends AggregateRootType.Provider with LazyLogging { module =>

  type ID = I
  type TID = TaggedID[ID]

  def nextId: ErrorOr[TID] = identifying.nextTID

  def shardName: String = _shardName

  def aggregateOf( id: Any  )( implicit model: DomainModel ): ActorRef = model( rootType = module.rootType, id )

  implicit def tagId( id: ID ): TID = identifying tag id

  override def toString: String = s"${getClass.safeSimpleName}(${identifying.idTag.name})"


  private[this] lazy val _shardName: String = org.atteo.evo.inflector.English.plural( identifying.idTag.name ).capitalize
}

object AggregateRootModule {

  trait Message[I] extends MessageLike {
    override type ID = I
  }

  trait Command[I] extends Message[I] with CommandLike 


  trait Event[I] extends EventLike {
    override type ID = I
  }
}
