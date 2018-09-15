package demesne

import akka.Done
import monix.eval.Task

/**
  * Created by rolfsd on 10/28/16.
  */
sealed abstract class StartTask {
  def task( bc: BoundedContext ): Task[StartTask.Result]
  def description: String
}

object StartTask {
  import scala.language.implicitConversions

  case class Result(
    resources: Map[Symbol, Any] = Map.empty[Symbol, Any],
    rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType]
  ) {
    override def toString: String = {
      s"""StartTask.Result( resources:[${resources.mkString( ", " )}] rootTypes:[${rootTypes
        .mkString( ", " )}] )"""
    }
  }

  implicit def resultFromResources( resources: Map[Symbol, Any] ): Result = {
    Result( resources = resources )
  }

  implicit def resultFromRootTypes( rootTypes: Set[AggregateRootType] ): Result = {
    Result( rootTypes = rootTypes )
  }

  implicit def resultFromDone( done: Done ): Result = Result()
  implicit def resultTaskfromDone( done: Task[Done] ): Task[Result] = {
    done map { _ =>
      Result()
    }
  }

  def withFunction( description: String )( fn: BoundedContext => StartTask.Result ): StartTask = {
    FunctionStartTask( fn, description )
  }

  def withBoundTask(
    description: String
  )( fn: BoundedContext => Task[StartTask.Result] ): StartTask = {
    BoundedStartTask( fn, description )
  }

  def withTask( description: String )( t: Task[StartTask.Result] ): StartTask = {
    val bind = (_: BoundedContext) => { t }
    BoundedStartTask( bind, description )
  }

  def empty( description: String ): StartTask = withTask( s"${description} (empty start task)" ) {
    Task now Done
  }

  sealed abstract class WrappingStartTask extends StartTask {

    def wrap( task: Task[Result] ): Task[Result] = {
      Task
        .now { scribe.info( s"starting: ${description} ..." ) }
        .flatMap { _ =>
          task
        }
        .flatMap { r =>
          scribe.info(
            s"finished: ${description} with resources:[${r.resources
              .mkString( ", " )}] and rootTypes:[${r.rootTypes.mkString( ", " )}]"
          )
          Task now r
        }
        .doOnFinish { ex =>
          ex foreach { x =>
            scribe.error( s"StartTask:[${description}] failed", x )
          }
          Task now { () }
        }
    }
  }

  final case class BoundedStartTask private[StartTask] (
    makeTask: BoundedContext => Task[Result],
    description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Result] = wrap( makeTask( bc ) )
  }

  final case class FunctionStartTask private[StartTask] (
    fn: BoundedContext => Result,
    override val description: String
  ) extends WrappingStartTask {
    override def task( bc: BoundedContext ): Task[Result] = wrap( Task { fn( bc ) } )
  }
}
