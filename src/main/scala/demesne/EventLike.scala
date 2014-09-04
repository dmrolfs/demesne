package demesne

import peds.commons.identifier.TaggedID


trait EventLike {
  type ID
  type TID = TaggedID[ID]
  def sourceId: TID
  def sourceTypeName: Option[String]
  // def version: Long
}

object EventLike {
  // def unapply( e: EventLike ): Option[(e.TID, Long)] = Some( (e.sourceId, e.version) )
  def unapply( e: EventLike ): Option[(e.TID, Option[String])] = Some( (e.sourceId, e.sourceTypeName) )
}
