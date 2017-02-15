package demesne

import scala.reflect.ClassTag
import omnibus.akka.publish.EventPublisher
import omnibus.commons.identifier.Identifying2


abstract class SagaModule[S, I]( implicit identifying: Identifying2.Aux[S, I] ) extends AggregateRootModule[S, I]()( identifying )


abstract class Saga[S, I](
  implicit identifying: Identifying2.Aux[S, I],
  evState: ClassTag[S] //,
//  evID: ClassTag[I0]
) extends AggregateRoot[S, I]()( identifying, evState /*, evID*/ ) {
  outer: AggregateRoot.Provider with EventPublisher =>
}
