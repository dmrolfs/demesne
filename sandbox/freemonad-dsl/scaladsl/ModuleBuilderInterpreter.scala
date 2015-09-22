package demesne.scaladsl

import scalaz.Id, scalaz.Scalaz._
import shapeless.Lens
import demesne.module.{ AggregateRootProps, DemesneModuleError, SimpleAggregateModule }


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
