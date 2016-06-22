package demesne

import scala.reflect.ClassTag
import peds.akka.publish.EventPublisher
import peds.archetype.domain.model.core.Identifying


abstract class SagaModule extends AggregateRootModule


abstract class Saga[S: ClassTag, I: ClassTag] extends AggregateRoot[S, I] { outer: EventPublisher => }
