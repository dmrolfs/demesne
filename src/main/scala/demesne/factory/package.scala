package demesne

import akka.actor.{ActorRef, Props}
import akka.contrib.pattern.ClusterSharding
import com.typesafe.scalalogging.StrictLogging
import peds.commons.log.Trace


package object factory extends StrictLogging {
  private val trace = Trace( "demesne.factory", logger )

  type ActorFactory = (DomainModel, AggregateRootType) => ( Props ) => ActorRef

  val systemFactory: ActorFactory = ( model: DomainModel, rootType: AggregateRootType ) => ( props: Props ) => trace.block( s"systemFactory($model, $rootType)" ) {
    model.system.actorOf( props, rootType.repositoryName )
  }

  val clusteredFactory: ActorFactory = ( model: DomainModel, rootType: AggregateRootType ) => ( props: Props ) => trace.block( s"clusteredFactory($model, $rootType)" ) {
    val repoSpec = EnvelopingAggregateRootRepository.specificationFor( model, rootType )
    val system = model.system
    ClusterSharding( system ).start(
      typeName = repoSpec.name,
      entryProps = Some( repoSpec.props ),
      idExtractor = repoSpec.idExtractor,
      shardResolver = repoSpec.shardResolver
    )

    ClusterSharding( system ).start(
      typeName = rootType.name,
      entryProps = Some( rootType.aggregateRootProps( model ) ),
      idExtractor = rootType.aggregateIdFor,
      shardResolver = rootType.shardIdFor
    )

    ClusterSharding( system ) shardRegion rootType.repositoryName
  }
}
