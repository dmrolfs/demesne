package demesne

import omnibus.commons.identifier.TaggedID


/**
  * Created by rolfsd on 6/30/16.
  */
case class EntityEnvelope( tid: TaggedID[_], message: Any ) {
  def id: Any = tid.id
}
