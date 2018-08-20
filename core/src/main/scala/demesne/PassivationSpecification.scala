package demesne

import scala.concurrent.duration.Duration
import akka.actor.NotInfluenceReceiveTimeout
import akka.cluster.sharding.ShardRegion.Passivate

object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }

  case class StopAggregateRoot[S](
    targetId: StopAggregateRoot[S]#TID
  ) extends MessageLike
      with NotInfluenceReceiveTimeout {
    override type A = S
  }
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Passivate = Passivate( stopMessage = message )
}
