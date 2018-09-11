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
  type TID[S, ID] = Id.Aux[S, ID]

  def saveSnapshotCommand[S, ID]( targetId: TID[S, ID] ): Any = SaveSnapshot[S, ID]( targetId )
  def snapshotInitialDelay: FiniteDuration
  def snapshotInterval: FiniteDuration

  def schedule[S, ID]( system: ActorSystem, target: ActorRef, tid: TID[S, ID] )(
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

case class SaveSnapshot[S, ID0]( override val targetId: SaveSnapshot[S, ID0]#TID )
    extends CommandLike
    with NotInfluenceReceiveTimeout {

  override type ID = ID0
  override type A = S
}
