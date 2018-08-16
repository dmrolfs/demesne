package demesne.index

import akka.persistence.journal.{ Tagged, WriteEventAdapter }
import demesne.EventLike

/**
  * Created by rolfsd on 2/5/17.
  */
class TaggingEventAdapter extends WriteEventAdapter {
  override def manifest( event: Any ): String = ""

  override def toJournal( event: Any ): Any = {
    event match {
      case e: EventLike if e.tags.nonEmpty => Tagged( e, e.tags )
      case _                               => event
    }
  }
}
