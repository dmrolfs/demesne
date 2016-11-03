package demesne

import akka.Done
import scalaz.concurrent.Task


/**
  * Created by rolfsd on 10/28/16.
  */
sealed abstract class StartTask {
  def task( bc: BoundedContext ): Task[Done]
  def description: String
}

object StartTask {
  def withFunction( description: String )( fn: BoundedContext => Done ): StartTask = DelayedDefinitionStartTask( fn, description )

  def withUnitTask( description: String )( t: Task[Done] ): StartTask = {
    val make = (_: BoundedContext) => { t }
    BoundedStartTask( make, description )
  }

  def withBoundTask( description: String )( makeTask: BoundedContext => Task[Done] ): StartTask = {
    BoundedStartTask( makeTask, description )
  }

  def empty( description: String ): StartTask = withBoundTask( s"${description} (empty start task)" ){ _ => Task now Done }


  final case class BoundedStartTask private[StartTask](
    makeTask: BoundedContext => Task[Done],
    description: String
  ) extends StartTask {
    override def task( bc: BoundedContext ): Task[Done] = makeTask( bc )
  }

  final case class DelayedDefinitionStartTask private[StartTask](
    fn: BoundedContext => Done,
    override val description: String
  ) extends StartTask {
    override def task( bc: BoundedContext ): Task[Done] = Task { fn(bc) }
  }
}