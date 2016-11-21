package demesne

import akka.Done
import scalaz.concurrent.Task
import com.typesafe.scalalogging.StrictLogging


/**
  * Created by rolfsd on 10/28/16.
  */
sealed abstract class StartTask {
  def task( bc: BoundedContext ): Task[Map[Symbol, Any]]
  def description: String
}

object StartTask extends StrictLogging {
  type Resources = BoundedContext.Resources

  def withFunction( description: String )( fn: BoundedContext => Resources ): StartTask = {
    FunctionStartTask( fn, description )
  }

  def withBoundTask( description: String )( fn: BoundedContext => Task[Resources] ): StartTask = {
    BoundedStartTask( fn, description )
  }

  def withTask( description: String )( t: Task[Resources] ): StartTask = {
    val bind = (_: BoundedContext) => { t }
    BoundedStartTask( bind, description )
  }

  def withUnitTask( description: String )(t: Task[Done] ): StartTask = {
    val bind = (_: BoundedContext) => { t }
    BoundedStartTask( bind andThen toEmptyTask, description )
  }

  def withUnitFunction( description: String )(fn: BoundedContext => Done ): StartTask = {
    FunctionStartTask( toEmptyResources compose fn, description )
  }

  def withBoundUnitTask( description: String )(makeTask: BoundedContext => Task[Done] ): StartTask = {
    BoundedStartTask( makeTask andThen toEmptyTask, description )
  }

  def empty( description: String ): StartTask = {
    withBoundTask( s"${description} (empty start task)" ){ _ => Task now Map.empty[Symbol, Any] }
  }

  val toEmptyResources: Done => Resources = (d: Done) => Map.empty[Symbol, Any]
  val toEmptyTask: Task[Done] => Task[Resources] = (t: Task[Done]) => t map { toEmptyResources }


  sealed abstract class WrappingStartTask extends StartTask {
    def wrap( task: Task[Resources] ): Task[Resources] = {
      Task
      .now { logger.info( "starting: {} ...", description ) }
      .flatMap { _ => task }
      .flatMap { resources =>
        logger.info( "finished: {} with resources:[{}]", description, resources.mkString( ", " ) )
        Task now resources
      }
      .onFinish { ex =>
        ex foreach { x => logger.error( s"StartTask:[${description}] failed", x ) }
        Task now { () }
      }
    }
  }


  final case class BoundedStartTask private[StartTask](
    makeTask: BoundedContext => Task[Resources],
    description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Resources] = wrap( makeTask(bc) )
  }


  final case class FunctionStartTask private[StartTask](
    fn: BoundedContext => Resources,
    override val description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Resources] = wrap( Task { fn(bc) } )
  }
}