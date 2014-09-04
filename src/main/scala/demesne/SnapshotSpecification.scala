package demesne

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorSystem, Cancellable }


object SnapshotSpecification {
  case object DoNotSnapshot extends SnapshotSpecification {
    override val snapshotInitialDelay: FiniteDuration = 1.second
    override val snapshotInterval: FiniteDuration = 1.second
  }
}

trait SnapshotSpecification { 
  def snapshotInitialDelay: FiniteDuration
  def snapshotInterval: FiniteDuration

  private[this] var cancellable: Option[Cancellable] = None

  final def schedule( 
    system: ActorSystem, 
    target: ActorRef, 
    saveSnapshotCommand: Any = SaveSnapshot 
  )( 
    implicit executor: ExecutionContext 
  ): Unit = {
    cancellable = Option( system.scheduler.schedule( snapshotInitialDelay, snapshotInterval, target, saveSnapshotCommand ) )
  }

  final def cancel: Unit = cancellable foreach { _.cancel() }
}
