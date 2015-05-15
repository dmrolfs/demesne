package demesne

import peds.commons.identifier.TaggedID


trait CommandLike {
  type ID
  type TID = TaggedID[ID]
  def targetId: TID
}

object CommandLike {
  def unapply( c: CommandLike ): Option[c.TID] = Some( c.targetId )
}
