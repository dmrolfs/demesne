package demesne

import peds.commons.identifier.TaggedID


trait EventLike extends Serializable {
  type ID
  type TID = TaggedID[ID]
  def sourceId: TID
  def tags: Set[String] = Set.empty[String]
  // def sourceTypeName: Option[String]
  // def version: Long
}

// object EventLike {
//   // def unapply( e: EventLike ): Option[(e.TID, Long)] = Some( (e.sourceId, e.version) )
//   def unapply( e: EventLike ): Option[(e.TID, Option[String])] = Some( (e.sourceId, e.sourceTypeName) )
// }
