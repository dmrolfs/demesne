package demesne.scaladsl

import scala.reflect.ClassTag
import akka.actor.Actor
import akka.actor.Actor.Receive
import scalaz.{ Lens => _, _ }, Scalaz._
import shapeless.{ Id => _, _ }
// import shapeless.syntax.typeable._
import peds.commons.V
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.{ AggregateRootProps, DemesneModuleError, SimpleAggregateModule }
import demesne.index.AggregateIndexSpec


object SimpleAggregateModuleBuilderInterpreter {
  def apply[S: ClassTag]: SimpleAggregateModuleBuilderInterpreter[S] = new SimpleAggregateModuleBuilderInterpreter[S]()
}

class SimpleAggregateModuleBuilderInterpreter[S: ClassTag]() extends AggregateModuleBuilderInterpreter[S] {
  
  trait SimpleModule extends BuilderModule {
    def indexesLens: Lens[SimpleModule, Seq[AggregateIndexSpec[_, _]]]
  }

  case class SimpleModuleImpl private[scaladsl](
    override val idTagO: Option[Symbol] = None,
    override val indexes: Seq[AggregateIndexSpec[_, _]] = Seq(),
    override val propsO: Option[AggregateRootProps] = None
  ) extends SimpleModule {
    override def trace: Trace[_] = Trace[SimpleModuleImpl]
    override def evState: ClassTag[S] = implicitly[ClassTag[S]]


    override val idTagOLens: Lens[BuilderModule, Option[Symbol]] = new Lens[BuilderModule, Option[Symbol]] {
      override def get( s: BuilderModule ): Option[Symbol] = s.idTagO
      override def set( s: BuilderModule )( a: Option[Symbol] ): BuilderModule = {
        SimpleModuleImpl( idTagO = a, indexes = s.indexes, propsO = s.propsO )
      }
    }

    override val propsOLens: Lens[BuilderModule, Option[AggregateRootProps]] = {
      new Lens[BuilderModule, Option[AggregateRootProps]] {
        override def get( s: BuilderModule ): Option[AggregateRootProps] = s.propsO
        override def set( s: BuilderModule )( a: Option[AggregateRootProps] ): BuilderModule = {
          SimpleModuleImpl( idTagO = s.idTagO, indexes = s.indexes, propsO = a )
        }
      }
    }

    override val indexesLens: Lens[SimpleModule, Seq[AggregateIndexSpec[_, _]]] = {
      new Lens[SimpleModule, Seq[AggregateIndexSpec[_, _]]] {
        override def get( s: SimpleModule ): Seq[AggregateIndexSpec[_, _]] = s.indexes
        override def set( s: SimpleModule )( a: Seq[AggregateIndexSpec[_, _]] ): SimpleModule = {
          SimpleModuleImpl( idTagO = s.idTagO, indexes = a, propsO = s.propsO )
        }
      }
    }

    override def toString: String = {
      s"${getClass.safeSimpleName}[${stateClass.safeSimpleName}](${aggregateIdTag} " +
      s"""indexes=${indexes.map(_.name).mkString("[", ",", "]")})"""
    }
  }


  private var _module: SimpleModule = SimpleModuleImpl()
  override def module: BuilderModule = _module
  override def module_=( newModule: BuilderModule ): Unit = {
    _module = newModule match {
      case m: SimpleModuleImpl => m
      case m: SimpleModule => m.asInstanceOf[SimpleModuleImpl] //DMR: Remove since unnecessary
      case m: BuilderModule => SimpleModuleImpl( idTagO = m.idTagO, indexes = m.indexes, propsO = m.propsO )
    }
  }

  def simpleOpStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = {
    case SetIndexes( indexes, next ) => ( next, _module.indexesLens.set( _module )( indexes ) )
    case AddIndex( index, next ) => ( next, _module.indexesLens.set( _module )( _module.indexes :+ index ) )
  }

  override def opStep[A]: PartialFunction[ModuleBuilderOpF[A], (Id[A], BuilderModule)] = simpleOpStep[A] orElse super.opStep[A] 

  import SimpleAggregateModuleBuilder._

  override def apply[A]( action: ModuleBuilderOp[A] ): Id[A] = action.runM( step )
}
