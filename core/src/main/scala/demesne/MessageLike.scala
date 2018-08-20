package demesne

import omnibus.identifier.Id

trait MessageLike {
  type A // AggregateRoot entity
  type TID = Id[A]
  def targetId: TID
}

object MessageLike {
  def unapply( m: MessageLike ): Option[m.TID] = Some( m.targetId )
}
