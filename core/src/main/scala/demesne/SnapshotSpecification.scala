package demesne

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorSystem, Cancellable, NotInfluenceReceiveTimeout }
import omnibus.identifier.Id

object SnapshotSpecification {
  case object DoNotSnapshot extends SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 1.second
    override val snapshotInterval: FiniteDuration = 1.second
  }
}

abstract class SnapshotSpecification { outer =>
  def saveSnapshotCommand[S]( tid: Id[S] ): Any = SaveSnapshot[S]( targetId = tid )
  def snapshotInitialDelay: FiniteDuration
  def snapshotInterval: FiniteDuration

  def schedule[S]( system: ActorSystem, target: ActorRef, tid: Id[S] )(
    implicit ec: ExecutionContext
  ): Cancellable = {
    system.scheduler.schedule(
      snapshotInitialDelay,
      snapshotInterval,
      target,
      saveSnapshotCommand( tid )
    )
  }
}

case class SaveSnapshot[S]( override val targetId: Id[S] )
    extends CommandLike
    with NotInfluenceReceiveTimeout {

  override type A = S
}
