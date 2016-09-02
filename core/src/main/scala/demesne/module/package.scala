package demesne

import scala.reflect.ClassTag
import peds.commons.util._


/**
 * Created by rolfsd on 5/23/15.
 */
package object module {
  trait DemesneModuleError extends Throwable


  final case class InfoStateIncompatibilityError[I: ClassTag, S: ClassTag]( info: I )
  extends IllegalArgumentException( 
    s"info [${info}: ${implicitly[ClassTag[I]].runtimeClass.safeSimpleName}] cannot be translated into " +
    s"state [${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" 
  ) with DemesneModuleError
}
