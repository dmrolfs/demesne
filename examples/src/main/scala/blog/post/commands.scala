package sample.blog.post


case class AddPost( override val targetId: AddPost#TID, content: PostContent ) extends PostModule.Command
case class GetContent( override val targetId: GetContent#TID ) extends PostModule.Command
case class ChangeBody( override val targetId: ChangeBody#TID, body: String ) extends PostModule.Command
case class ChangeTitle( override val targetId: ChangeBody#TID, title: String ) extends PostModule.Command
case class Publish( override val targetId: Publish#TID ) extends PostModule.Command
case class Delete( override val targetId: Publish#TID ) extends PostModule.Command
