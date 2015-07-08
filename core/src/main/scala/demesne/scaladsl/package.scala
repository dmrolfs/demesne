package demesne

import akka.actor.Actor.Receive
import scalaz.Free


package object scaladsl {
  type SimpleModuleBuilderOp[A] = Free[SimpleModuleBuilderOpF, A]
}
