package demesne

import peds.akka.publish.EventPublisher


trait SagaModule extends AggregateRootModule


abstract class Saga[S] extends AggregateRoot[S] { outer: EventPublisher => }
