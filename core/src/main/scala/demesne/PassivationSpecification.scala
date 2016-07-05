package demesne

import akka.actor.PoisonPill

import scala.concurrent.duration.Duration
import akka.cluster.sharding.ShardRegion.Passivate


object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }

  case class StopAggregateRoot[ID]( targetId: ID )
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Passivate = Passivate( stopMessage = message )
}
