package demesne

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorSystem, Cancellable, NotInfluenceReceiveTimeout }
import omnibus.commons.identifier.TaggedID

object SnapshotSpecification {
  case object DoNotSnapshot extends SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 1.second
    override val snapshotInterval: FiniteDuration = 1.second
  }
}

abstract class SnapshotSpecification { outer =>
  def saveSnapshotCommand[ID]( tid: TaggedID[ID] ): Any = SaveSnapshot( targetId = tid )
  def snapshotInitialDelay: FiniteDuration
  def snapshotInterval: FiniteDuration

  def schedule[ID]( system: ActorSystem, target: ActorRef, tid: TaggedID[ID] )(
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

case class SaveSnapshot( override val targetId: TaggedID[Any] )
    extends CommandLike
    with NotInfluenceReceiveTimeout {
  override type ID = Any
}
