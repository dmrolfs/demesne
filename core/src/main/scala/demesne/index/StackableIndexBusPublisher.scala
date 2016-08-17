package demesne.index

import demesne.{ AggregateRootType, DomainModel }
import peds.akka.publish.{ EventPublisher, Publisher, silent }
import peds.commons.util.Chain._
import peds.commons.log.Trace


trait StackableIndexBusPublisher extends EventPublisher { outer: DomainModel.Provider with AggregateRootType.Provider =>
  private val trace = Trace[StackableIndexBusPublisher]

  abstract override def publish: Publisher = trace.block( "publish" ) {
    val bus = IndexBus.bus( model.indexBus, outer.rootType )( _: AggregateIndexSpec[_, _, _] )
    outer.rootType.indexes.filter( _.relaySubscription == IndexBusSubscription ).foldLeft( super.publish ){ _ +> bus(_ ) }
  }
}
