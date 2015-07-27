package demesne

import scala.reflect.ClassTag
import akka.actor.Props
import akka.actor.Actor.Receive
// import akka.agent.Agent
import peds.commons.util._


/**
 * Created by rolfsd on 5/23/15.
 */
package object module {
  type AggregateRootProps = (DomainModel, AggregateRootType) => Props


  trait DemesneModuleError extends Throwable


  final case class InfoStateIncompatibilityError[I: ClassTag, S: ClassTag]( info: I )
  extends IllegalArgumentException( 
    s"info [${info}: ${implicitly[ClassTag[I]].runtimeClass.safeSimpleName}] cannot be translated into " +
    s"state [${implicitly[ClassTag[S]].runtimeClass.safeSimpleName}]" 
  ) with DemesneModuleError

}
