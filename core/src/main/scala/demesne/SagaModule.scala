package demesne

import scala.reflect.ClassTag
import omnibus.akka.publish.EventPublisher
import omnibus.identifier.Identifying

abstract class SagaModule[S, ID](
  implicit override val identifying: Identifying.Aux[S, ID]
) extends AggregateRootModule[S, ID]

abstract class Saga[S, ID](
  implicit override val identifying: Identifying.Aux[S, ID],
  stateType: ClassTag[S]
) extends AggregateRoot[S, ID] {
  outer: AggregateRoot.Provider with EventPublisher =>
}
