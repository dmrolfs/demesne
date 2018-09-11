package demesne

import omnibus.identifier.Id

trait MessageLike {
  type A // AggregateRoot entity
  type ID
  type TID = Id.Aux[A, ID]
  def targetId: TID
}

object MessageLike {
  def unapply( m: MessageLike ): Option[m.TID] = Some( m.targetId )
}
