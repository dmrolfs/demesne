package demesne

import scala.concurrent.{ ExecutionContext, Future }
import akka.actor.ActorSystem
import akka.util.Timeout
import scalaz._, Scalaz._
import shapeless.syntax.typeable._
import peds.commons.log.Trace
import demesne.factory._


trait CommonInitializeAggregateActorType extends InitializeAggregateActorType  { self: AggregateRootModule =>
  import CommonInitializeAggregateActorType._

  def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : V[Future[Unit]] = trace.block( "initializer" ) { Future.successful{ }.successNel }


  override def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): V[Future[Unit]] = trace.block( s"initialize" ) {
    import scalaz.Validation.FlatMap._

    val rootType = aggregateRootType

    for {
      smf <- ( checkSystem(props) |@| checkModel(props) |@| checkFactory(props) ) { (s, m, f) => (s, m, f) }
      (system, model, factory) = smf
      f1 <- initializer( rootType, model, props )
    } yield {
  //todo: combine this with above for-comp via a monad transformer?
      for {
        _ <- f1
        _ <- registerWithModel( model, rootType, factory )
      } yield ()
    }
  }

  private def checkSystem( props: Map[Symbol, Any] ): V[ActorSystem] = trace.block( "checkSystem" ) {
    props get demesne.SystemKey flatMap { _.cast[ActorSystem] } map { _.successNel } getOrElse { 
      UnspecifiedActorSystemError( demesne.SystemKey ).failureNel 
    }
  }

  private def checkModel( props: Map[Symbol, Any] ): V[DomainModel] = trace.block( "checkModel" ) {
    props get demesne.ModelKey flatMap { _.cast[DomainModel] } map { _.successNel } getOrElse {
      UnspecifiedDomainModelError( demesne.ModelKey ).failureNel
    }
  }

  private def checkFactory( props: Map[Symbol, Any] ): V[ActorFactory] = trace.block( "checkFactory" ) {
    val factory = props get demesne.FactoryKey flatMap { _.cast[ActorFactory] } getOrElse { demesne.factory.systemFactory }
    factory.successNel
  }

  private def registerWithModel( model: DomainModel, rootType: AggregateRootType, factory: ActorFactory )( implicit to: Timeout ): Future[Unit] = trace.block( s"registerWithModel($rootType)" ) {
    model.registerAggregateType( rootType, factory )
  }
}

object CommonInitializeAggregateActorType { 
  val trace = Trace[CommonInitializeAggregateActorType.type]

  final case class UnspecifiedActorSystemError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ActorSystem required at initialization property [$expectedKey]" ) with DemesneError
  
  final case class UnspecifiedDomainModelError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"DomainModel required at initialization property [$expectedKey]" ) with DemesneError
}