package sample.blog.post


case class PostContent( author: String, title: String, body: String ) {
  def isIncomplete: Boolean = author.isEmpty || title.isEmpty
}

object PostContent {
  val empty = PostContent( "", "", "" )
}
