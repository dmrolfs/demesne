package demesne


trait CommandLike extends AggregateMessage

object CommandLike {
  def unapply( c: CommandLike ): Option[c.TID] = Some( c.targetId )
}
