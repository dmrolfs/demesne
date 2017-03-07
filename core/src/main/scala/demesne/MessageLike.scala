package demesne

import omnibus.commons.identifier.TaggedID


trait MessageLike {
  type ID
  type TID = TaggedID[ID]
  def targetId: TID
}

object MessageLike {
  def unapply( m: MessageLike ): Option[m.TID] = Some( m.targetId )
}
