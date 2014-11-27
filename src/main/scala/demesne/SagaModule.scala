package demesne

import peds.akka.publish.EventPublisher


trait SagaModule extends AggregateRootModule { module: AggregateModuleInitializationExtension => }

trait SagaModuleCompanion extends AggregateRootModuleCompanion


abstract class Saga[S: AggregateStateSpecification] extends AggregateRoot[S] { outer: EventPublisher => }
