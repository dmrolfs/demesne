package demesne.index

import scala.language.existentials
import scala.reflect.ClassTag
import omnibus.commons.identifier.TaggedID


/**
  * Created by rolfsd on 8/16/16.
  */
final case class IndexIdentifier private[index](
  topic: String,
  keyTag: ClassTag[_],
  idTag: ClassTag[_],
  valueTag: ClassTag[_]
) {
  def tagged: TaggedID[IndexIdentifier] = IndexIdentifier toTaggedId this
}

object IndexIdentifier {
  def make[K: ClassTag, I: ClassTag, V: ClassTag]( topic: String ): IndexIdentifier = {
    IndexIdentifier(
      topic,
      keyTag = implicitly[ClassTag[K]],
      idTag = implicitly[ClassTag[I]],
      valueTag = implicitly[ClassTag[V]]
    )
  }

  val Tag: Symbol = 'index

  implicit def toTaggedId( id: IndexIdentifier ): TaggedID[IndexIdentifier] = TaggedID( Tag, id )
}
