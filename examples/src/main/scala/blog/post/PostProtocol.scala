package sample.blog.post

import demesne.AggregateProtocol
import omnibus.identifier.ShortUUID

object PostPrototol extends AggregateProtocol[Post, Post#ID] {
  case class AddPost( override val targetId: AddPost#TID, content: PostContent ) extends Command
  case class GetContent( override val targetId: GetContent#TID ) extends Command
  case class ChangeBody( override val targetId: ChangeBody#TID, body: String ) extends Command
  case class ChangeTitle( override val targetId: ChangeBody#TID, title: String ) extends Command
  case class Publish( override val targetId: Publish#TID ) extends Command
  case class Delete( override val targetId: Publish#TID ) extends Command

  case class PostAdded( override val sourceId: PostAdded#TID, content: PostContent ) extends Event
  case class BodyChanged( override val sourceId: BodyChanged#TID, body: String ) extends Event
  case class TitleChanged(
    override val sourceId: TitleChanged#TID,
    oldTitle: String,
    newTitle: String
  ) extends Event
  case class PostPublished(
    override val sourceId: PostPublished#TID,
    author: String,
    title: String
  ) extends Event
  case class Deleted( override val sourceId: Deleted#TID ) extends Event
}
