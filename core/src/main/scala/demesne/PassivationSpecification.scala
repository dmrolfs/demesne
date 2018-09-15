package demesne

import scala.concurrent.duration.Duration
import scala.language.existentials
import akka.actor.NotInfluenceReceiveTimeout
import akka.cluster.sharding.ShardRegion.Passivate
import omnibus.identifier.Id.Aux
import omnibus.identifier.{ Id, Identifying }

object PassivationSpecification {
  case object DoNotPassivate extends PassivationSpecification {
    override def inactivityTimeout: Duration = Duration.Undefined
  }

  case class StopAggregateRoot[A0, ID](
    fooId: StopAggregateRoot[A0, ID]#TID
  )(
    implicit val identifying: Identifying.Aux[A0, ID]
  ) extends MessageLike
      with NotInfluenceReceiveTimeout {

    override def targetId: TID = fooId
    override type A = A0
    override type ID = identifying.ID
    override type TID = identifying.TID
  }
}

trait PassivationSpecification {
  def inactivityTimeout: Duration
  def passivationMessage( message: Any ): Passivate = Passivate( stopMessage = message )
}
