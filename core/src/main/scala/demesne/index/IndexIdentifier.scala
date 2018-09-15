package demesne.index

import scala.language.existentials
import scala.reflect.ClassTag

/**
  * Created by rolfsd on 8/16/16.
  */
final case class IndexIdentifier private[index] (
  topic: String,
  keyTag: ClassTag[_],
  idTag: ClassTag[_],
  valueTag: ClassTag[_]
)

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

//  implicit def toTaggedId[E]( id: IndexIdentifier ): TaggedID[IndexIdentifier] = TaggedID( Tag, id )
}
