package demesne

import akka.actor.ActorRef
import omnibus.identifier.Identifying
import omnibus.core.syntax.clazz._

abstract class AggregateRootModule[S, ID0]( implicit val identifying: Identifying.Aux[S, ID0] )
    extends AggregateRootType.Provider { module =>

  type ID = ID0
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

  abstract class Message[A0, ID0](
    implicit val identifying: Identifying.Aux[A0, ID0]
  ) extends MessageLike {
    override type A = A0
    override type ID = ID0
  }

  abstract class Command[A, ID0]( implicit override val identifying: Identifying.Aux[A, ID0] )
      extends Message[A, ID0]
      with CommandLike

  abstract class Event[A0, ID0](
    implicit val identifying: Identifying.Aux[A0, ID0]
  ) extends EventLike {
    override type A = A0
    override type ID = ID0
  }
}
