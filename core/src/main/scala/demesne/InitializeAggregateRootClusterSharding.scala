package demesne

import scala.concurrent.{ ExecutionContext, Future }
import akka.contrib.pattern.ClusterSharding
import scalaz._, Scalaz._


trait InitializeAggregateRootClusterSharding extends CommonInitializeAggregateActorType { self: AggregateRootModule =>
  override def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ): V[Future[Unit]] = {
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
