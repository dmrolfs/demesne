package sample.blog.post

import demesne.EventLike


sealed trait Event extends EventLike {
  override type ID = PostModule.ID
  // override val sourceTypeName: Option[String] = Option( PostModule.aggregateRootType.name )
}

case class PostAdded( override val sourceId: PostAdded#TID, content: PostContent ) extends Event
case class BodyChanged( override val sourceId: BodyChanged#TID, body: String ) extends Event
case class TitleChanged( override val sourceId: TitleChanged#TID, oldTitle: String, newTitle: String ) extends Event
case class PostPublished( override val sourceId: PostPublished#TID, author: String, title: String ) extends Event
case class Deleted( override val sourceId: Deleted#TID ) extends Event
