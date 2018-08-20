package demesne

import scala.reflect.ClassTag
import omnibus.akka.publish.EventPublisher
import omnibus.identifier.Identifying

abstract class SagaModule[S: Identifying] extends AggregateRootModule[S]

abstract class Saga[S: Identifying: ClassTag] extends AggregateRoot[S] {
  outer: AggregateRoot.Provider with EventPublisher =>
}
