package demesne

import scala.reflect.ClassTag
import omnibus.akka.publish.EventPublisher


abstract class SagaModule extends AggregateRootModule


abstract class Saga[S: ClassTag, I: ClassTag] extends AggregateRoot[S, I] { outer: AggregateRoot.Provider with EventPublisher => }
