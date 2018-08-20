package demesne

import akka.actor.ActorRef
import shapeless.the
import omnibus.identifier.Identifying
import omnibus.core.syntax.clazz._

abstract class AggregateRootModule[S: Identifying] extends AggregateRootType.Provider { module =>

  val identifying: Identifying[S] = the[Identifying[S]]
  type ID = identifying.ID
  type TID = identifying.TID

  def nextId: TID = identifying.next

  def shardName: String = _shardName

  def aggregateOf( id: Any )( implicit model: DomainModel ): ActorRef =
    model( rootType = module.rootType, id )

//  implicit def tagId( id: ID ): TID = identifying tag id

  override def toString: String = s"${getClass.safeSimpleName}(${identifying.label})"

  private[this] lazy val _shardName: String =
    org.atteo.evo.inflector.English.plural( identifying.label ).capitalize
}

object AggregateRootModule {

  trait Message[A0] extends MessageLike {
    override type A = A0
  }

  trait Command[A] extends Message[A] with CommandLike

  trait Event[A0] extends EventLike {
    override type A = A0
  }
}
