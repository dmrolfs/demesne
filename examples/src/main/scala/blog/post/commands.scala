package sample.blog.post

import demesne.CommandLike


sealed trait Command extends CommandLike {
  override type ID = PostModule.ID
}

case class AddPost( override val targetId: AddPost#TID, content: PostContent ) extends Command
case class GetContent( override val targetId: GetContent#TID ) extends Command
case class ChangeBody( override val targetId: ChangeBody#TID, body: String ) extends Command
case class ChangeTitle( override val targetId: ChangeBody#TID, title: String ) extends Command
case class Publish( override val targetId: Publish#TID ) extends Command
case class Delete( override val targetId: Publish#TID ) extends Command
