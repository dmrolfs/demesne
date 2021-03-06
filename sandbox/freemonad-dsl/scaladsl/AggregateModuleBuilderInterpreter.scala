package demesne.scaladsl

import scala.reflect.ClassTag
import scalaz.{ Lens => _, _ }, Scalaz._
import shapeless.Lens
import omnibus.commons.log.Trace
import omnibus.commons.util._
import demesne.module.{ AggregateRootProps, DemesneModuleError }


object AggregateModuleBuilderInterpreter {
  def apply[S: ClassTag]: AggregateModuleBuilderInterpreter[S] = new AggregateModuleBuilderInterpreter[S]()
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
