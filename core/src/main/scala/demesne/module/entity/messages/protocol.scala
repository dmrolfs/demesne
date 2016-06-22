package demesne.module.entity.messages

import demesne.AggregateRootModule


sealed trait EntityMessage
abstract class EntityCommand extends AggregateRootModule.Command[Any] with EntityMessage
abstract class EntityEvent extends AggregateRootModule.Event[Any] with EntityMessage


case class Add( override val targetId: Add#TID, info: Any ) extends EntityCommand
case class Rename( override val targetId: Rename#TID, name: String ) extends EntityCommand
case class Reslug( override val targetId: Reslug#TID, slug: String ) extends EntityCommand
case class Disable( override val targetId: Disable#TID ) extends EntityCommand
case class Enable( override val targetId: Enable#TID ) extends EntityCommand


case class Added( override val sourceId: Added#TID, info: Any ) extends EntityEvent
case class Renamed( override val sourceId: Renamed#TID, oldName: String, newName: String ) extends EntityEvent
case class Reslugged( override val sourceId: Reslugged#TID, oldSlug: String, newSlug: String ) extends EntityEvent
case class Disabled( override val sourceId: Disabled#TID, slug: String ) extends EntityEvent
case class Enabled( override val sourceId: Enabled#TID, slug: String ) extends EntityEvent





//import demesne.AggregateRootModule
//import peds.archetype.domain.model.core.{Entity, EntityIdentifying}
//
//
//sealed trait EntityMessage {
//  type That
//  def identifying: EntityIdentifying[That] = implicitly[EntityIdentifying[That]]
//}
//
//abstract class EntityCommand[E <: Entity : EntityIdentifying] extends AggregateRootModule.Command[E#ID] with EntityMessage {
//  override type That = E
//  override def identifying: EntityIdentifying[E] = implicitly[EntityIdentifying[E]]
//}
//
//abstract class EntityEvent[E <: Entity : EntityIdentifying] extends AggregateRootModule.Event[E#ID] with EntityMessage {
//  override type That = E
//  override def identifying: EntityIdentifying[E] = implicitly[EntityIdentifying[E]]
//}
//
//
//case class Add[E <: Entity : EntityIdentifying]( override val targetId: Add[E]#TID, info: Any ) extends EntityCommand
//case class Rename[E <: Entity : EntityIdentifying]( override val targetId: Rename[E]#TID, name: String ) extends EntityCommand
//case class Reslug[E <: Entity : EntityIdentifying]( override val targetId: Reslug[E]#TID, slug: String ) extends EntityCommand
//case class Disable[E <: Entity : EntityIdentifying]( override val targetId: Disable[E]#TID ) extends EntityCommand
//case class Enable[E <: Entity : EntityIdentifying]( override val targetId: Enable[E]#TID ) extends EntityCommand
//
//
//case class Added[E <: Entity : EntityIdentifying]( override val sourceId: Added[E]#TID, info: Any ) extends EntityEvent
//
//case class Renamed[E <: Entity : EntityIdentifying](
//                                                     override val sourceId: Renamed[E]#TID,
//                                                     oldName: String,
//                                                     newName: String
//                                                   ) extends EntityEvent
//
//case class Reslugged[E <: Entity : EntityIdentifying](
//                                                       override val sourceId: Reslugged[E]#TID,
//                                                       oldSlug: String,
//                                                       newSlug: String
//                                                     ) extends EntityEvent
//
//case class Disabled[E <: Entity : EntityIdentifying]( override val sourceId: Disabled[E]#TID, slug: String ) extends EntityEvent
//case class Enabled[E <: Entity : EntityIdentifying]( override val sourceId: Enabled[E]#TID, slug: String ) extends EntityEvent
