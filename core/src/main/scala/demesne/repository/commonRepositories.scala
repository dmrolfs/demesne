package demesne.repository

import demesne.{AggregateRootType, DomainModel}
import AggregateRootRepository.{ClusteredAggregateContext, LocalAggregateContext}
import akka.actor.Props


/**
  * Created by rolfsd on 8/31/16.
  */
object CommonClusteredRepository {
  def props( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps ): Props = {
    Props( new CommonClusteredRepository( model, rootType, makeAggregateProps ) )
  }
}

class CommonClusteredRepository( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps )
extends CommonRepository( model, rootType, makeAggregateProps ) with ClusteredAggregateContext



object CommonLocalRepository {
  def props( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps ): Props = {
    Props( new CommonLocalRepository( model, rootType, makeAggregateProps ) )
  }
}

class CommonLocalRepository( model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps )
extends CommonRepository( model, rootType, makeAggregateProps ) with LocalAggregateContext



abstract class CommonRepository(model: DomainModel, rootType: AggregateRootType, makeAggregateProps: AggregateRootProps )
extends EnvelopingAggregateRootRepository( model, rootType ) { outer: AggregateRootRepository.AggregateContext =>
  override def aggregateProps: Props = makeAggregateProps( model, rootType )
}