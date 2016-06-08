package demesne

import peds.akka.publish.EventPublisher


trait SagaModule[I] extends AggregateRootModule[I]


abstract class Saga[S] extends AggregateRoot[S] { outer: EventPublisher => }
