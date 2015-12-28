package demesne

import akka.actor.{ ActorContext, ActorRef }
import akka.cluster.sharding.ClusterSharding
import shapeless.Typeable
import com.typesafe.scalalogging.StrictLogging
import peds.commons.log.Trace


package object factory extends StrictLogging {
  private val trace = Trace( "demesne.factory", logger )

  //todo: this factory has grown cumbersome and awkward; need to DRY it up
  type ActorFactory = (DomainModel, Option[ActorContext]) => ( AggregateRootType, String ) => ActorRef

  /**
   * supports typesafe casting to ActorFactory since type is a type def rather than a class/trait.
   */
  implicit val typeableActorFactory = new Typeable[ActorFactory] {
    override def cast( t: Any ): Option[ActorFactory] = scala.util.Try[ActorFactory]{ t.asInstanceOf[ActorFactory] }.toOption
    override val describe: String = "ActorFactory"
  }

  val systemFactory: ActorFactory = {
    ( model: DomainModel, context: Option[ActorContext] ) => ( rootType: AggregateRootType, name: String ) => trace.block( s"systemFactory($model, $context)($rootType)" ) {
      model.system.actorOf( rootType.aggregateRootProps(model), name )
    }
  }

  val contextFactory: ActorFactory = {
    ( model: DomainModel, context: Option[ActorContext] ) => ( rootType: AggregateRootType, name: String ) => trace.block( s"contextFactory($model, $context)($rootType)") {
      val result = for {
        ctx <- context
      } yield ctx.actorOf( rootType.aggregateRootProps( model ), name )

      result getOrElse model.system.deadLetters
    }
  }

  val clusteredFactory: ActorFactory = {
      ( model: DomainModel, context: Option[ActorContext] ) => ( rootType: AggregateRootType, name: String ) => trace.block( s"clusteredFactory($model, $context)($rootType)" ) {
      ClusterSharding( model.system ) shardRegion rootType.name
    }
  }
}
