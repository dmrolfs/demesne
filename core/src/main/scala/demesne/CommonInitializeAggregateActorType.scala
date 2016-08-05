package demesne

import akka.Done

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging

import scalaz._
import Scalaz._
import shapeless._
import peds.commons.Valid
import peds.commons.log.Trace
import peds.commons.util._
import demesne.factory._



trait CommonInitializeAggregateActorType extends InitializeAggregateActorType with LazyLogging {
  self: AggregateRootType.Provider =>

  import CommonInitializeAggregateActorType._

  def initializer( 
    rootType: AggregateRootType, 
    model: DomainModel, 
    props: Map[Symbol, Any] 
  )( 
    implicit ec: ExecutionContext
  ) : Valid[Future[Done]] = trace.block( "initializer" ) { Future.successful{ Done }.successNel }


  override def initialize( props: Map[Symbol, Any] )( implicit ec: ExecutionContext, to: Timeout ): Valid[Future[Done]] = trace.block( s"initialize" ) {
    import scalaz.Validation.FlatMap._

    val rt = self.rootType

    for {
      smfc <- ( checkSystem(props) |@| checkModel(props) |@| checkFactory(props) |@| checkConfiguration(props)) { (s, m, f, c) =>
        (s, m, f, c)
      }
      (system, model, factory, conf) = smfc
      f1 <- initializer( rt, model, props + (demesne.ConfigurationKey -> conf) )
    } yield {
  //todo: combine this with above for-comp via a monad transformer?
      for {
        _ <- f1
        _ <- registerWithModel( model, rt, factory )
      } yield Done
    }
  }

  private def checkSystem( props: Map[Symbol, Any] ): Valid[ActorSystem] = {
    logger.info( "checking module ActorSystem" )
    val SystemType = TypeCase[ActorSystem]

    props.get( demesne.SystemKey ) match {
      case Some( SystemType(sys) ) => sys.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[ActorSystem]( demesne.SystemKey, x )
      case None => Validation failureNel UnspecifiedActorSystemError( demesne.SystemKey )
    }
  }

  private def checkModel( props: Map[Symbol, Any] ): Valid[DomainModel] = {
    logger.info( "checking module DomainModel" )
    val ModelType = TypeCase[DomainModel]

    props.get( demesne.ModelKey ) match {
      case Some( ModelType(m) ) => m.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[DomainModel]( demesne.ModelKey, x )
      case None => Validation failureNel UnspecifiedDomainModelError( demesne.ModelKey )
    }
  }

  private def checkFactory( props: Map[Symbol, Any] ): Valid[ActorFactory] =  {
    logger.info( "checking module ActorFactory" )

    val FactoryType = TypeCase[ActorFactory]

    props.get( demesne.FactoryKey ) match {
      case Some( FactoryType(f) ) => f.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[ActorFactory]( demesne.FactoryKey, x )
      case None => demesne.factory.contextFactory.successNel[Throwable]
    }
  }

  private def checkConfiguration( props: Map[Symbol, Any] ): Valid[Config] = {
    logger.info( "checking module configuration" )
    val ConfigType = TypeCase[Config]

    props.get( demesne.ConfigurationKey ) match {
      case Some( ConfigType(c) ) => c.successNel[Throwable]
      case Some( x ) => Validation failureNel IncompatibleTypeForPropertyError[Config]( demesne.ConfigurationKey, x )
      case None => {
        logger.info( "Default module configuration is not provided. Loading via ConfigFactory." )
        ConfigFactory.load().successNel[Throwable]
      }
    }
  }

  private def registerWithModel(
    model: DomainModel,
    rootType: AggregateRootType,
    factory: ActorFactory
  )(
    implicit to: Timeout
  ): Future[Done] = trace.block( s"registerWithModel($rootType)" ) {
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
