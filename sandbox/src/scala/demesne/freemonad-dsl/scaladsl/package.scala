package demesne

import scalaz.Free


package object scaladsl {
  type ModuleBuilderOp[A] = Free[ModuleBuilderOpF, A]
}
