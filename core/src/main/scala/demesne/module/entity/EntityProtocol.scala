package demesne.module.entity

import demesne.{AggregateProtocol, AggregateRootModule}


abstract class EntityProtocol[I] extends AggregateProtocol[I] { protocol =>

  case class Add( override val targetId: Add#TID, info: Option[Any] = None ) extends Command
  case class Rename( override val targetId: Rename#TID, name: String ) extends Command
  case class Reslug( override val targetId: Reslug#TID, slug: String ) extends Command
  case class Disable( override val targetId: Disable#TID ) extends Command
  case class Enable( override val targetId: Enable#TID ) extends Command


  def tags: Set[String] = Set.empty[String]

  abstract class TaggedEvent extends Event {
    override def tags: Set[String] = protocol.tags
  }

  case class Added( override val sourceId: Added#TID, info: Option[Any] = None ) extends TaggedEvent
  case class Renamed( override val sourceId: Renamed#TID, oldName: String, newName: String ) extends TaggedEvent
  case class Reslugged( override val sourceId: Reslugged#TID, oldSlug: String, newSlug: String ) extends TaggedEvent
  case class Disabled( override val sourceId: Disabled#TID, slug: String ) extends TaggedEvent
  case class Enabled( override val sourceId: Enabled#TID, slug: String ) extends TaggedEvent
}
