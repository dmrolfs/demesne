package demesne

import scala.concurrent.duration.Duration
import akka.cluster.sharding.ShardRegion.Passivate


object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Any = Passivate( stopMessage = message )
}
