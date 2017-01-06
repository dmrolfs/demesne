package demesne


trait CommandLike extends MessageLike

object CommandLike {
  def unapply( c: CommandLike ): Option[c.TID] = Some( c.targetId )
}
