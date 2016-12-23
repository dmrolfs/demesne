package demesne.index

import demesne.{ AggregateRootType, DomainModel }
import peds.akka.publish.{ EventPublisher, Publisher, silent }
import peds.commons.util.Chain._


trait StackableIndexBusPublisher extends EventPublisher { outer: DomainModel.Provider with AggregateRootType.Provider =>
  abstract override def publish: Publisher = {
    val bus = IndexBus.bus( model.indexBus, outer.rootType )( _: IndexSpecification )
    outer.rootType.indexes.filter( _.relaySubscription == IndexBusSubscription ).foldLeft( super.publish ){ _ +> bus(_ ) }
  }
}
