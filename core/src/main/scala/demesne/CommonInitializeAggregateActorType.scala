package demesne

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import akka.actor.ActorSystem
import akka.util.Timeout

import scalaz._
import Scalaz._
import shapeless._
import peds.commons.Valid
import peds.commons.log.Trace
import peds.commons.util._
import demesne.factory._



trait CommonInitializeAggregateActorType extends InitializeAggregateActorType  { self: AggregateRootType.Provider =>
  import CommonInitializeAggregateActorType._

  def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : Valid[Future[Unit]] = trace.block( "initializer" ) { Future.successful{ }.successNel }


  override def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): Valid[Future[Unit]] = trace.block( s"initialize" ) {
    import scalaz.Validation.FlatMap._

    val rt = self.rootType

    for {
      smf <- ( checkSystem(props) |@| checkModel(props) |@| checkFactory(props) ) { (s, m, f) => (s, m, f) }
      (system, model, factory) = smf
      f1 <- initializer( rt, model, props )
    } yield {
  //todo: combine this with above for-comp via a monad transformer?
      for {
        _ <- f1
        _ <- registerWithModel( model, rt, factory )
      } yield ()
    }
  }

  private def checkSystem( props: Map[Symbol, Any] ): Valid[ActorSystem] = trace.block( "checkSystem" ) {
    val System = TypeCase[ActorSystem]

    props.get( demesne.SystemKey ) match {
      case Some( System(sys) ) => sys.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[ActorSystem]( demesne.SystemKey, x )
      case None => Validation failureNel UnspecifiedActorSystemError( demesne.SystemKey )
    }
  }

  private def checkModel( props: Map[Symbol, Any] ): Valid[DomainModel] = trace.block( "checkModel" ) {
    val Model = TypeCase[DomainModel]

    props.get( demesne.ModelKey ) match {
      case Some( Model(m) ) => m.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[DomainModel]( demesne.ModelKey, x )
      case None => Validation failureNel UnspecifiedDomainModelError( demesne.ModelKey )
    }
  }

  private def checkFactory( props: Map[Symbol, Any] ): Valid[ActorFactory] = trace.block( "checkFactory" ) {
    val Factory = TypeCase[ActorFactory]

    props.get( demesne.FactoryKey ) match {
      case Some( Factory(f) ) => f.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[ActorFactory]( demesne.FactoryKey, x )
      case None => demesne.factory.contextFactory.successNel[Throwable]
    }
  }

  private def registerWithModel(
    model: DomainModel,
    rootType: AggregateRootType,
    factory: ActorFactory
  )(
    implicit to: Timeout
  ): Future[Unit] = trace.block( s"registerWithModel($rootType)" ) {
    model.registerAggregateType( rootType, factory )
  }
}

object CommonInitializeAggregateActorType { 
  val trace = Trace[CommonInitializeAggregateActorType.type]

  final case class IncompatibleTypeForPropertyError[T: ClassTag] private[demesne]( key: Symbol, value: Any )
  extends IllegalArgumentException(
    s"For key[${key.name}] expected type:[${implicitly[ClassTag[T]].runtimeClass.safeSimpleName}] " +
    s"but got type:[${value.getClass.safeSimpleName}]"
  )

  final case class UnspecifiedActorSystemError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"ActorSystem required at initialization property [$expectedKey]" ) with DemesneError
  
  final case class UnspecifiedDomainModelError private[demesne]( expectedKey: Symbol )
  extends IllegalArgumentException( s"DomainModel required at initialization property [$expectedKey]" ) with DemesneError
}
