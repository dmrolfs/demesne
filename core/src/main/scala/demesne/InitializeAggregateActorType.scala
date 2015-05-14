package demesne

import scala.concurrent.{ ExecutionContext, Future }
import scalaz._, Scalaz._
import akka.util.Timeout


trait InitializeAggregateActorType { 
  def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): V[Future[Unit]]
}

object InitializeAggregateActorType {
  def apply( props: Map[Symbol, Any] )( types: InitializeAggregateActorType* )( implicit ec: ExecutionContext, to: Timeout ): V[Future[Unit]] = {
    types.toList.map{ _ initialize props }.sequence map { init => Future.sequence( init ) map { _ => () } }
  }
}
