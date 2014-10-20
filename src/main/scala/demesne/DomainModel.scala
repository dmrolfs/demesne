package demesne

import akka.actor.{ActorRef, ActorSystem}
import akka.agent.Agent
import demesne.factory._
import peds.commons.log.Trace

import scala.concurrent.Await
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


  def register( name: String )( implicit system: ActorSystem ): DomainModel = trace.block( s"register($name)($system)" ) {
    val key = Key( name, system )
    val model = new DomainModelImpl( name )
    val alteration = modelRegistry alter { _ + (key -> model) }
    Await.ready( alteration, 200.millis )
    model
  }

  def apply( name: String )( implicit system: ActorSystem ): DomainModel = trace.block( s"apply(${name})(${system})" ) {
    val k = Key( name, system )
    trace( s"key=$k" )

    val result = modelRegistry().getOrElse(
      k,
      throw new NoSuchElementException( s"no DomainModel registered for name=$name and system=$system" )
    )

    require(
      result.system == system,
      s"requested DomainModel, $name, exists under system=${result.system} not expected=${system}"
    )

    result
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  private case class Key( name: String, system: ActorSystem )
  // private case class Key( name: String )

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

    override def aggregateOf( name: String, id: Any ): AggregateRootRef = trace.block( s"aggregateOf($name, $id)" ) {
      trace( s"""name = $name; system = $system; id = $id; aggregateTypeRegistry = ${registry().mkString("[", ",", "]")} """ )

      registry().get( name ) map { rr =>
        val (rootType, aggregateRepository) = rr
        AggregateRootRef( rootType, id, aggregateRepository )
      } getOrElse {
        throw new IllegalStateException(s"DomainModel type registry does not have root type:${name}")
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit = trace.block( "registerAggregateType" ) {
      registry send { r =>
        trace.block( s"[name=${name}, system=${system}] registry send ${rootType}" ) {
          trace( s"DomainModel.name= $name" )
          if ( r.contains( rootType.name ) ) r
          else {
            val repoRef = factory( this, rootType )( EnvelopingAggregateRootRepository.props( this, rootType ) )
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
