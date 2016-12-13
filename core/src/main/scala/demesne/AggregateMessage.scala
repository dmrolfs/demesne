package demesne

import peds.commons.identifier.TaggedID


trait AggregateMessage {
  type ID
  type TID = TaggedID[ID]
  def targetId: TID
}

object AggregateMessage {
  def unapply( m: AggregateMessage ): Option[m.TID] = Some( m.targetId )
}
