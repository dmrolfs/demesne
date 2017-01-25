package demesne

import scala.concurrent.duration.Duration
import akka.actor.NotInfluenceReceiveTimeout
import akka.cluster.sharding.ShardRegion.Passivate


object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }

  case class StopAggregateRoot[I0]( 
    targetId: StopAggregateRoot[I0]#TID 
  ) extends MessageLike with NotInfluenceReceiveTimeout { 
    type ID = I0 
  }
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Passivate = Passivate( stopMessage = message )
}
