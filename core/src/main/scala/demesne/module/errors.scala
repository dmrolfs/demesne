package demesne

import scala.reflect.ClassTag
import omnibus.core.syntax.clazz._

/**
  * Created by rolfsd on 5/23/15.
  */
trait DemesneModuleError extends Throwable with DemesneError

final case class InfoStateIncompatibilityError[I: ClassTag, S: ClassTag]( info: I )
    extends IllegalArgumentException(
      s"info [${info}: ${implicitly[ClassTag[I]].runtimeClass.safeSimpleName}] cannot be translated into " +
      s"state [${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]"
    )
    with DemesneModuleError
