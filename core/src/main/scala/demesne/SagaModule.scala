package demesne

import scala.reflect.ClassTag
import peds.akka.publish.EventPublisher
import peds.archetype.domain.model.core.Identifying


abstract class SagaModule[I: Identifying] extends AggregateRootModule[I]


abstract class Saga[S: ClassTag] extends AggregateRoot[S] { outer: EventPublisher => }
