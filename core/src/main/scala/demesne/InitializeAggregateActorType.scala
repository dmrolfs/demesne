package demesne

import scala.concurrent.{ ExecutionContext, Future }
import akka.util.Timeout


trait InitializeAggregateActorType { 
  def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): V[Future[Unit]]
}
