package demesne

import omnibus.identifier.Id

/**
  * Created by rolfsd on 6/30/16.
  */
case class EntityEnvelope( tid: Id[_], message: Any ) {
  def id: Any = tid.value
}
