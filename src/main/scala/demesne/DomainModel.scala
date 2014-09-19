package demesne

import akka.actor.{ ActorRef, ActorSystem }
import peds.commons.log.Trace
import demesne.factory._


trait DomainModel {
  def basename: String
  def system: ActorSystem
  def aggregateOf( rootType: AggregateRootType, id: Any ): AggregateRootRef = aggregateOf( rootType.name, id )
  def aggregateOf( name: String, id: Any ): AggregateRootRef
  def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit
  def shutdown(): Unit
}

object DomainModel {
  trait Provider {
    def model: DomainModel
    def system: ActorSystem
  }

  object Provider{
    def unapply( p: Provider ): Option[(DomainModel, ActorSystem)] = Some( ( p.model, p.system ) )
  }


  def apply( basename: String = "domain.model" )( implicit system: ActorSystem ): DomainModel = {
    modelRegistry.get( basename ) getOrElse {
      val result = new DomainModelImpl( basename )
      modelRegistry += ( basename -> result )
      result
    }
  }

  def unapply( dm: DomainModel ): Option[(String)] = Some( dm.basename )

  private[this] var modelRegistry: Map[String, DomainModel] = Map()

  private case class DomainModelImpl(
    override val basename: String
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

      if ( aggregateTypeRegistry.contains( name ) ) {
        val (rootType, aggregateRepository) = aggregateTypeRegistry( name )
        AggregateRootRef( rootType, id, aggregateRepository )
      } else {
        throw new IllegalStateException( s"DomainModel type registry does not have root type:${name}" )
      }
    }

    override def registerAggregateType( rootType: AggregateRootType, factory: ActorFactory ): Unit = trace.block( "registerAggregateType" ) {
      trace( s"rootType = $rootType" )
      trace( s"factory = ${factory}" )

      if ( !aggregateTypeRegistry.contains( rootType.name ) ) {
        val repoRef = factory( system, rootType ){ EnvelopingAggregateRootRepository.props( rootType ) }
        val entry = (rootType, repoRef)
        aggregateTypeRegistry += ( rootType.name -> entry )
      }

      trace( s"""aggregateTypeRegistry = ${aggregateTypeRegistry.mkString("[", ",", "]")} """ )
      require( aggregateTypeRegistry.contains( rootType.name ), "failed postcondition" )
    }

    override def shutdown(): Unit = system.shutdown()
  }
}
