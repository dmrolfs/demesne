package demesne

import scala.reflect.ClassTag
import omnibus.akka.publish.EventPublisher
import omnibus.commons.identifier.Identifying2


abstract class SagaModule extends AggregateRootModule


abstract class Saga[S: ClassTag, I: ClassTag]( implicit identifying: Identifying2.Aux[S, I] )
  extends AggregateRoot[S, I]()( identifying ) { outer: AggregateRoot.Provider with EventPublisher => }
