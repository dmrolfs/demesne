package demesne

import omnibus.identifier.Id

trait EventLike extends Serializable {
  type A
  type TID = Id[A]
  def sourceId: TID
  def tags: Set[String] = Set.empty[String]
}

object EventLike {
  def unapply( e: EventLike ): Option[( e.TID, Set[String] )] = Some( ( e.sourceId, e.tags ) )
}
