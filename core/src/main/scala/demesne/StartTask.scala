package demesne

import akka.Done
import com.typesafe.scalalogging.StrictLogging

import scalaz.concurrent.Task


/**
  * Created by rolfsd on 10/28/16.
  */
sealed abstract class StartTask {
  def task( bc: BoundedContext ): Task[Done]
  def description: String
}

object StartTask extends StrictLogging {
  def withFunction( description: String )( fn: BoundedContext => Done ): StartTask = DelayedDefinitionStartTask( fn, description )

  def withUnitTask( description: String )( t: Task[Done] ): StartTask = {
    val make = (_: BoundedContext) => { t }
    BoundedStartTask( make, description )
  }

  def withBoundTask( description: String )( makeTask: BoundedContext => Task[Done] ): StartTask = {
    BoundedStartTask( makeTask, description )
  }

  def empty( description: String ): StartTask = withBoundTask( s"${description} (empty start task)" ){ _ => Task now Done }


  sealed abstract class WrappingStartTask extends StartTask {
    def wrap( task: Task[Done] ): Task[Done] = {
      Task
      .now { logger.info( "starting: {} ...", description ); Done }
      .flatMap { _ => task }
      .onFinish { ex =>
        ex match {
          case None => logger.info( "finished: {}", description )
          case Some( ex ) => logger.error( s"StartTask:[${description}] failed", ex )
        }
        Task now { () }
      }
    }
  }

  final case class BoundedStartTask private[StartTask](
    makeTask: BoundedContext => Task[Done],
    description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Done] = wrap( makeTask(bc) )
  }

  final case class DelayedDefinitionStartTask private[StartTask](
    fn: BoundedContext => Done,
    override val description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Done] = wrap( Task { fn(bc) } )
  }
}