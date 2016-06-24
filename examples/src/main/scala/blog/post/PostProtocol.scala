package sample.blog.post

import demesne.AggregateProtocol


object PostPrototol extends AggregateProtocol[PostModule.ID] {
  case class AddPost( override val targetId: AddPost#TID, content: PostContent ) extends CommandMessage
  case class GetContent( override val targetId: GetContent#TID ) extends CommandMessage
  case class ChangeBody( override val targetId: ChangeBody#TID, body: String ) extends CommandMessage
  case class ChangeTitle( override val targetId: ChangeBody#TID, title: String ) extends CommandMessage
  case class Publish( override val targetId: Publish#TID ) extends CommandMessage
  case class Delete( override val targetId: Publish#TID ) extends CommandMessage

  case class PostAdded( override val sourceId: PostAdded#TID, content: PostContent ) extends EventMessage
  case class BodyChanged( override val sourceId: BodyChanged#TID, body: String ) extends EventMessage
  case class TitleChanged( override val sourceId: TitleChanged#TID, oldTitle: String, newTitle: String ) extends EventMessage
  case class PostPublished( override val sourceId: PostPublished#TID, author: String, title: String ) extends EventMessage
  case class Deleted( override val sourceId: Deleted#TID ) extends EventMessage
}


