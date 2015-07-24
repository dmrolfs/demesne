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


class SimpleAggregateModuleBuilderInterpreter[S: ClassTag]() extends AggregateModuleBuilderInterpreter[S] {
  import SimpleAggregateModuleBuilder._
  
  case class SimpleModule private[scaladsl](
    override val idTagO: Option[Symbol] = None,
    override val indexes: Seq[AggregateIndexSpec[_, _]] = Seq(),
    override val propsO: Option[AggregateRootProps] = None
  ) extends BuilderModule {
    override def trace: Trace[_] = Trace[SimpleModule]
    override def evState: ClassTag[S] = implicitly[ClassTag[S]]

    override val idTagOLens: Lens[BuilderModule, Option[Symbol]] = new Lens[BuilderModule, Option[Symbol]] {
      override def get( s: BuilderModule ): Option[Symbol] = s.idTagO
      override def set( s: BuilderModule )( a: Option[Symbol] ): BuilderModule = {
        BuilderModuleImpl( idTagO = a, propsO = s.propsO )
      }
    }

    override val propsOLens: Lens[BuilderModule, Option[AggregateRootProps]] = {
      new Lens[BuilderModule, Option[AggregateRootProps]] {
        override def get( s: BuilderModule ): Option[AggregateRootProps] = s.propsO
        override def set( s: BuilderModule )( a: Option[AggregateRootProps] ): BuilderModule = {
          BuilderModuleImpl( idTagO = s.idTagO, propsO = a )
        }
      }
    }

    override def toString: String = {
      s"${getClass.safeSimpleName}[${stateClass.safeSimpleName}](${aggregateIdTag} " +
      s"""indexes=${indexes.map(_.name).mkString("[", ",", "]")})"""
    }
  }


  private var _module: SimpleModule = SimpleModule()
  override def module: BuilderModule = _module
  override def module_=( newModule: BuilderModule ): Unit = {
    _module = newModule match {
      case m: SimpleModule => m
      case m: BuilderModule => SimpleModule( idTagO = m.idTagO, propsO = m.propsO )
    }
  }

  def myOpStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = {
    case SetIndexes( indexes, next ) => ( next, _module.copy( indexes = indexes ) )
    case AddIndex( index, next ) => ( next, _module.copy( indexes = module.indexes :+ index ) )
  }

  override def opStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = myOpStep[A] orElse super.opStep[A] 

  override def apply[A]( action: ModuleBuilderOp[A] ): Id[A] = action.runM( step )
}

object SimpleAggregateModuleBuilderInterpreter {
  final case object UndefinedAggregateIdTagError 
  extends IllegalArgumentException( "aggregateIdTag must be defined for Module" ) with DemesneModuleError

  final case object UndefinedAggregateRootPropsError
  extends IllegalArgumentException( "aggregate root actor props must be specificed for Module" ) with DemesneModuleError
}
