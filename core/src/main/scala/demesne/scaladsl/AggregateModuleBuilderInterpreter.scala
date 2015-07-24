package demesne.scaladsl

import scala.reflect.ClassTag
import akka.actor.Actor
import akka.actor.Actor.Receive
import scalaz.{ Lens => _, _ }, Scalaz._
import shapeless.{ Id => _, _ }
import peds.commons.V
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.{ AggregateRootProps, DemesneModuleError, SimpleAggregateModule }
import demesne.register.AggregateIndexSpec


trait ModuleBuilderInterpreter[S] {
  import ModuleBuilderInterpreter._

  def apply[A]( action: ModuleBuilderOp[A] ): Id[A]

  def step[A]( action: ModuleBuilderOpF[A] ): Id[A] = {
    val (result, m) = opStep( action )
    module = m
    result
  }

  def opStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)]

  def module: BuilderModule
  def module_=( newModule: BuilderModule ): Unit

  trait BuilderModule extends SimpleAggregateModule[S] {
    def idTagO: Option[Symbol]
    def idTagOLens: Lens[BuilderModule, Option[Symbol]]

    def propsO: Option[AggregateRootProps]
    def propsOLens: Lens[BuilderModule, Option[AggregateRootProps]]

    // override def evState: ClassTag[S] = implicitly[ClassTag[S]]
    override def aggregateIdTag: Symbol = idTagO getOrElse { throw UndefinedAggregateIdTagError }
    override def aggregateRootPropsOp: AggregateRootProps = propsO getOrElse { throw UndefinedAggregateRootPropsError }
  }
}

object ModuleBuilderInterpreter {
  final case object UndefinedAggregateIdTagError 
  extends IllegalArgumentException( "aggregateIdTag must be defined for Module" ) with DemesneModuleError

  final case object UndefinedAggregateRootPropsError
  extends IllegalArgumentException( "aggregate root actor props must be specificed for Module" ) with DemesneModuleError
}

class AggregateModuleBuilderInterpreter[S: ClassTag]() extends ModuleBuilderInterpreter[S] {
  import ModuleBuilderInterpreter._
  import AggregateModuleBuilder._

  case class BuilderModuleImpl private[scaladsl](
    override val idTagO: Option[Symbol] = None,
    override val propsO: Option[AggregateRootProps] = None
  ) extends BuilderModule {
    override def trace: Trace[_] = Trace[BuilderModuleImpl]
    override def evState: ClassTag[S] = implicitly[ClassTag[S]]

    override val idTagOLens: Lens[BuilderModule, Option[Symbol]] = new Lens[BuilderModule, Option[Symbol]] {
      override def get( s: BuilderModule ): Option[Symbol] = s.idTagO
      override def set( s: BuilderModule )( a: Option[Symbol] ): BuilderModule = {
        BuilderModuleImpl( idTagO = a, propsO = s.propsO )
      }
    }

    override val propsOLens: Lens[BuilderModule, Option[AggregateRootProps]] = new Lens[BuilderModule, Option[AggregateRootProps]] {
      override def get( s: BuilderModule ): Option[AggregateRootProps] = s.propsO
      override def set( s: BuilderModule )( a: Option[AggregateRootProps] ): BuilderModule = {
        BuilderModuleImpl( idTagO = s.idTagO, propsO = a )
      }
    }

    override def toString: String = s"${getClass.safeSimpleName}[${stateClass.safeSimpleName}](${aggregateIdTag})"
  }


  private var _module: BuilderModule = BuilderModuleImpl()
  override def module: BuilderModule = _module
  override def module_=( newModule: BuilderModule ): Unit = { _module = newModule }

  override def opStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = {
    case SetIdTag( newIdTag, next ) => ( next, module.idTagOLens.set( module )( Some(newIdTag) ) )

    case SetProps( props, next ) => ( next, module.propsOLens.set( module )( Some(props) ) )

    case Build( onBuild ) => {
      def checkIdTag( tag: Option[Symbol] ): \/[DemesneModuleError, Symbol] = {
        tag map { _.right } getOrElse { UndefinedAggregateIdTagError.left }
      }

      def checkProps( props: Option[AggregateRootProps] ): \/[DemesneModuleError, AggregateRootProps] = {
        props map { _.right } getOrElse { UndefinedAggregateRootPropsError.left }
      }

      val result = for {
        _ <- checkIdTag( module.idTagO )
        _ <- checkProps( module.propsO )
      } yield module

      result match {
        case \/-( m ) => ( onBuild(m), module )
        case -\/( ex ) => throw ex
      }
    }
  }

  override def apply[A]( action: ModuleBuilderOp[A] ): Id[A] = action.runM( step )
}
