package demesne

import scala.concurrent.duration._
import akka.contrib.pattern.ShardRegion.Passivate


object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }
}

trait PassivationSpecification { 
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Any = Passivate( stopMessage = message )
}
