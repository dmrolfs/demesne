package sample.blog.post


case class PostAdded( override val sourceId: PostAdded#TID, content: PostContent ) extends PostModule.Event
case class BodyChanged( override val sourceId: BodyChanged#TID, body: String ) extends PostModule.Event
case class TitleChanged( override val sourceId: TitleChanged#TID, oldTitle: String, newTitle: String ) extends PostModule.Event
case class PostPublished( override val sourceId: PostPublished#TID, author: String, title: String ) extends PostModule.Event
case class Deleted( override val sourceId: Deleted#TID ) extends PostModule.Event
