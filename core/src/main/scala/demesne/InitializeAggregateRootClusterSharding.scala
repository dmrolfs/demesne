package demesne

import scala.concurrent.{ ExecutionContext, Future }
import scalaz._, Scalaz._
import akka.contrib.pattern.ClusterSharding
import peds.commons.V


trait InitializeAggregateRootClusterSharding extends CommonInitializeAggregateActorType { self: AggregateRootModule =>
  abstract override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ): V[Future[Unit]] = peds.commons.log.Trace("InitializeAggregateRootClusterSharding").block( "initializer" ) {
    super.initializer( rootType, model, props )

    ClusterSharding( model.system )
      .start(
        typeName = rootType.name,
        entryProps = Some( rootType.aggregateRootProps(model) ),
        idExtractor = rootType.aggregateIdFor,
        shardResolver = rootType.shardIdFor
      )

    Future.successful{ }.successNel
  }
}
