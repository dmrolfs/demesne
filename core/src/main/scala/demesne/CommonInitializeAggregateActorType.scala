package demesne

import scala.concurrent.{ ExecutionContext, Future }
import akka.actor.ActorSystem
import akka.util.Timeout
import scalaz._, Scalaz._
import shapeless.syntax.typeable._
import demesne.factory._


trait CommonInitializeAggregateActorType extends InitializeAggregateActorType  { self: AggregateRootModule =>
  import CommonInitializeAggregateActorType._

  def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : V[Future[Unit]] = Future.successful{ }.successNel


  override def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): V[Future[Unit]] = {
    import scalaz.Validation.FlatMap._

    val rootType = aggregateRootType

    for {
      smf <- ( checkSystem(props) |@| checkModel(props) |@| checkFactory(props) ) { (s, m, f) => (s, m, f) }
      (system, model, factory) = smf
      f1 <- initializer( rootType, model, props )
      f2 = registerWithModel( model, rootType, factory )
    } yield Future.sequence( Seq(f1, f2) ) flatMap { _ => Future.successful{ } }
  }

  private def checkSystem( props: Map[Symbol, Any] ): V[ActorSystem] = {
    props get demesne.SystemKey flatMap { _.cast[ActorSystem] } map { _.successNel } getOrElse { 
      UnspecifiedActorSystemError( demesne.SystemKey ).failureNel 
    }
  }

  private def checkModel( props: Map[Symbol, Any] ): V[DomainModel] = {
    props get demesne.ModelKey flatMap { _.cast[DomainModel] } map { _.successNel } getOrElse {
      UnspecifiedDomainModelError( demesne.ModelKey ).failureNel
    }
  }

  private def checkFactory( props: Map[Symbol, Any] ): V[ActorFactory] = {
    val factory = props get demesne.FactoryKey flatMap { _.cast[ActorFactory] } getOrElse { demesne.factory.systemFactory }
    factory.successNel
  }

  private def registerWithModel( model: DomainModel, rootType: AggregateRootType, factory: ActorFactory )( implicit to: Timeout ): Future[Unit] = trace.block( s"registerWithModel($rootType)" ) {
    model.registerAggregateType( rootType, factory )
  }
}

object CommonInitializeAggregateActorType { 
  final case class UnspecifiedActorSystemError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ActorSystem required at initialization property [$expectedKey]" ) with DemesneError
  
  final case class UnspecifiedDomainModelError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"DomainModel required at initialization property [$expectedKey]" ) with DemesneError
}
