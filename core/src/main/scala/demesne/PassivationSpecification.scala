package demesne

import akka.contrib.pattern.ShardRegion.Passivate

import scala.concurrent.duration._


object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Any = Passivate( stopMessage = message )
}
