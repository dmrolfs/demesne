package demesne

import akka.actor.{ActorRef, ActorSystem}
import demesne.factory._
import peds.commons.log.Trace


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
    modelRegistry.getOrElse(
      k,
      {
        val result = new DomainModelImpl( name )
        modelRegistry += k -> result
        trace( s"domain model registry = ${modelRegistry}" )
        result
      }
    )
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.name )

  private[this] case class Key( name: String, system: ActorSystem )

  private[this] var modelRegistry: Map[Key, DomainModel] = Map()

  private case class DomainModelImpl(
    override val name: String
  )(
    implicit override val system: ActorSystem
  ) extends DomainModel {
    val trace = Trace[DomainModelImpl]

    type RootTypeRef = (AggregateRootType, ActorRef)

    var aggregateTypeRegistry: Map[String, RootTypeRef] = Map()

    override def aggregateOf( name: String, id: Any ): AggregateRootRef = trace.block( "aggregateOf" ) {
      trace( s"name = $name" )
      trace( s"id = $id" )
      trace( s"""aggregateTypeRegistry = ${aggregateTypeRegistry.mkString("[", ",", "]")} """ )

      if ( aggregateTypeRegistry contains name ) {
        val (rootType, aggregateRepository) = aggregateTypeRegistry( name )
        AggregateRootRef( rootType, id, aggregateRepository )
      } else {
        throw new IllegalStateException( s"DomainModel type registry does not have root type:${name}" )
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit = trace.block( "registerAggregateType" ) {
      trace( s"DomainModel.name= $name" )
      trace( s"DomainModel.system= $system" )
      trace( s"rootType = $rootType" )
      trace( s"factory = ${factory}" )

      if ( !aggregateTypeRegistry.contains( rootType.name ) ) {
        val repoRef = factory( system, rootType )( EnvelopingAggregateRootRepository.props( rootType ) )
        val entry = (rootType, repoRef)
        aggregateTypeRegistry += ( rootType.name -> entry )
      }

      trace( s"""aggregateTypeRegistry = ${aggregateTypeRegistry.mkString("[", ",", "]")} """ )
      require( aggregateTypeRegistry.contains( rootType.name ), "failed postcondition" )
    }

    override def shutdown(): Unit = system.shutdown()

    override def toString: String = s"DomainModelImpl(name=$name, system=$system)"
  }
}
