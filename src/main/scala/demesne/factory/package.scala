package demesne

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.contrib.pattern.ClusterSharding
import peds.commons.log.Trace
import peds.commons.util._


package object factory {
  type ActorFactory = (ActorSystem, AggregateRootType) => ( => Props ) => ActorRef

  def systemFactory( system: ActorSystem, rootType: AggregateRootType )( props: => Props ): ActorRef = {
    system.actorOf( props, rootType.repositoryName )
  }

  def clusteredFactory( system: ActorSystem, rootType: AggregateRootType )( props: => Props ): ActorRef = {
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
