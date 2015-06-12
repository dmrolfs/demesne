package demesne.register

import demesne.{ AggregateRootType, DomainModel }
import peds.akka.publish.{ EventPublisher, Publisher, silent }
import peds.commons.util.Chain._
import peds.commons.log.Trace


trait StackableRegisterBusPublisher extends EventPublisher { outer: DomainModel.Provider with AggregateRootType.Provider =>
  private val trace = Trace[StackableRegisterBusPublisher]

  abstract override def publish: Publisher = trace.block( "publish" ) {
    val bus = RegisterBus.bus( model.registerBus, meta )( _: AggregateIndexSpec[_, _] )
    meta.indexes.filter( _.relaySubscription == RegisterBusSubscription ).foldLeft( super.publish ){ _ +> bus(_) }
  }
}
