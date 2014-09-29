package demesne

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.contrib.pattern.ClusterSharding


package object factory {
  type ActorFactory = (ActorSystem, AggregateRootType) => ( Props ) => ActorRef

  val systemFactory: ActorFactory = ( system: ActorSystem, rootType: AggregateRootType ) => ( props: Props ) => {
    system.actorOf( props, rootType.repositoryName )
  }

  val clusteredFactory: ActorFactory = ( system: ActorSystem, rootType: AggregateRootType ) => ( props: Props ) => {
    val repoSpec = EnvelopingAggregateRootRepository specificationFor rootType
    ClusterSharding( system ).start(
      typeName = repoSpec.name,
      entryProps = Some( repoSpec.props ),
      idExtractor = repoSpec.idExtractor,
      shardResolver = repoSpec.shardResolver
    )

    ClusterSharding( system ).start(
      typeName = rootType.name,
      entryProps = Some( rootType.aggregateRootProps ),
      idExtractor = rootType.aggregateIdFor,
      shardResolver = rootType.shardIdFor
    )

    ClusterSharding( system ) shardRegion rootType.repositoryName
  }
}
