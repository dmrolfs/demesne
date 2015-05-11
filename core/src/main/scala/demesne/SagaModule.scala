package demesne

import peds.akka.publish.EventPublisher


// // trait SagaModule extends AggregateRootModule { module => }

trait SagaModule extends AggregateRootModule


abstract class Saga[S: AggregateStateSpecification] extends AggregateRoot[S] { outer: EventPublisher => }
