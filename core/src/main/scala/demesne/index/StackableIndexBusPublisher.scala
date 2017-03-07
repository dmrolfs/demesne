package demesne.index

import akka.actor.{ Actor, ActorLogging }
import demesne.{ AggregateRootType, DomainModel }
import omnibus.akka.publish.{ EventPublisher, Publisher, silent }
import omnibus.commons.util.Chain._


trait StackableIndexBusPublisher extends EventPublisher { outer: Actor with ActorLogging with DomainModel.Provider with AggregateRootType.Provider =>
  abstract override def publish: Publisher = {
    val bus = IndexBus.bus( model.indexBus, outer.rootType )( _: IndexSpecification )
    outer.rootType.indexes.filter( _.relaySubscription == IndexBusSubscription ).foldLeft( super.publish ){ _ +> bus(_ ) }
  }
}
