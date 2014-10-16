package demesne

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import demesne.factory._
import peds.commons.log.Trace
import akka.agent.Agent

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._


trait DomainModel {
  def name: String
  def system: ActorSystem
  def aggregateOf( rootType: AggregateRootType, id: Any ): AggregateRootRef = aggregateOf( rootType.name, id )
  def aggregateOf( name: String, id: Any ): AggregateRootRef
  def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit
  def shutdown(): Unit
}

object DomainModel {
  val trace = Trace[DomainModel.type]

  trait Provider {
    def model: DomainModel
    def system: ActorSystem
  }

  object Provider{
    def unapply( p: Provider ): Option[(DomainModel, ActorSystem)] = Some( ( p.model, p.system ) )
  }


  def apply( name: String = "domain.model" )( implicit system: ActorSystem ): DomainModel = trace.block( s"(${name}, ${system})" ) {
    val k = Key( name, system )
    modelRegistry().getOrElse(
      k,
      {
        trace( s"registering DomainModel($name) with global registry" )
        val result = new DomainModelImpl( name )
        val alteration = modelRegistry alter { _ + (k -> result) } // use alter and await on Future instead?
        Await.result( alteration, 200.millis )
        result
      }
    )
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  private case class Key( name: String, system: ActorSystem )

  import scala.concurrent.ExecutionContext.global
  private val modelRegistry: Agent[Map[Key, DomainModel]] = Agent( Map[Key, DomainModel]() )( global )

  private object DomainModelImpl {
    type RootTypeRef = (AggregateRootType, ActorRef)
    type AggregateRegistry = Map[String, RootTypeRef]
  }

  private case class DomainModelImpl(
    override val name: String
  )(
    implicit override val system: ActorSystem
  ) extends DomainModel {
    import demesne.DomainModel.DomainModelImpl._

    val trace = Trace[DomainModelImpl]

    // default dispatcher is okay since mutations are limited to bootstrap.
    val registry: Agent[AggregateRegistry] = Agent( Map[String, RootTypeRef]() )( system.dispatcher )

    override def aggregateOf( name: String, id: Any ): AggregateRootRef = trace.block( "aggregateOf" ) {
      trace( s"name = $name" )
      trace( s"id = $id" )
      trace( s"""aggregateTypeRegistry = ${registry().mkString("[", ",", "]")} """ )

      registry().get( name ) map { rr =>
        val (rootType, aggregateRepository) = rr
        AggregateRootRef( rootType, id, aggregateRepository )
      } getOrElse {
        throw new IllegalStateException(s"DomainModel type registry does not have root type:${name}")
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit = trace.block( "registerAggregateType" ) {
      registry send { r =>
        trace.block( s"registry send $rootType" ) {
          trace( s"DomainModel.name= $name" )
          if ( r.contains( rootType.name ) ) r
          else {
            val repoRef = factory( system, rootType )( EnvelopingAggregateRootRepository.props( rootType ) )
            val entry = (rootType, repoRef)
            r + ( rootType.name -> entry )
          }
        }
      }
    }

    override def shutdown(): Unit = system.shutdown()

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }
}
