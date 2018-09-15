package demesne.index

import scala.reflect.ClassTag

/**
  * Created by rolfsd on 8/15/16.
  */
case class IndexedValue[I: ClassTag, +V: ClassTag]( id: I, value: V )
