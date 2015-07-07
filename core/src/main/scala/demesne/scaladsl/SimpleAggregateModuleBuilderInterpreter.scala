package demesne.scaladsl

import scala.reflect.ClassTag
import akka.actor.Actor
import akka.actor.Actor.Receive
import scalaz._, Scalaz._
import peds.commons.V
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.{ AggregateRootProps, DemesneModuleError, SimpleAggregateModule }
import demesne.register.AggregateIndexSpec


trait ModuleBuilderInterpreter {
  def apply[A]( action: SimpleModuleBuilderOp[A] ): Id[A]
}

case class SimpleAggregateModuleBuilderInterpreter[S: ClassTag]() extends ModuleBuilderInterpreter {
  import SimpleAggregateModuleBuilderInterpreter._

  type Acceptance = AggregateRoot.Acceptance[S]

  case class SimpleModule(
    idTagO: Option[Symbol] = None,
    override val indexes: Seq[AggregateIndexSpec[_, _]] = Seq(),
    // override val acceptance: Acceptance = SimpleModule.emptyAcceptance,
    propsO: Option[AggregateRootProps] = None
  ) extends SimpleAggregateModule[S] {
    override def trace: Trace[_] = Trace[SimpleModule]
    override val evState: ClassTag[S] = implicitly[ClassTag[S]]
    override def aggregateIdTag: Symbol = idTagO getOrElse { throw UndefinedAggregateIdTagError }
    override def aggregateRootPropsOp: AggregateRootProps = propsO getOrElse { throw UndefinedAggregateRootPropsError }

    override def toString: String = {
      s"${getClass.safeSimpleName}[${stateClass.safeSimpleName}](${aggregateIdTag} " +
      s"""indexes=${indexes.map(_.name).mkString("[", ",", "]")})"""
    }
  }

  object SimpleModule {
    val emptyAcceptance: Acceptance = peds.commons.util.emptyBehavior[(Any, S), S]
  }


  var module: SimpleModule = SimpleModule()

  def step[A]( action: SimpleModuleBuilderOpF[SimpleModuleBuilderOp[A]] ): Id[SimpleModuleBuilderOp[A]] = {
    action match {
      case SetIdTag( newIdTag, next ) => {
        module = module.copy( idTagO = Some(newIdTag) )
        next
      }

      case AddIndex( index, next ) => {
        module = module.copy( indexes = module.indexes :+ index )
        next
      }

      case SetProps( props, next ) => {
        module = module.copy( propsO = Some(props) )
        next
      }

      case SetAcceptance( newAcceptance, next ) => {
        val result = scala.util.Try {
          // module = module.copy( acceptance = newAcceptance.asInstanceOf[Acceptance] )
        }

        result match {
          case scala.util.Success(_) => next
          case scala.util.Failure( ex ) => throw ex
        }
      }

      case Build( onBuild ) => {
        val result = for {
          _ <- checkIdTag( module.idTagO )
          _ <- checkProps( module.propsO )
        } yield module

        result match {
          case \/-( m ) => onBuild( m )
          case -\/( ex ) => throw ex
        }
      }
    }
  }

  def checkIdTag( tag: Option[Symbol] ): \/[DemesneModuleError, Symbol] = {
    tag map { _.right } getOrElse { UndefinedAggregateIdTagError.left }
  }

  def checkProps( props: Option[AggregateRootProps] ): \/[DemesneModuleError, AggregateRootProps] = {
    props map { _.right } getOrElse { UndefinedAggregateRootPropsError.left }
  }

  override def apply[A]( action: SimpleModuleBuilderOp[A] ): Id[A] = action.runM( step )
}

object SimpleAggregateModuleBuilderInterpreter {
  final case object UndefinedAggregateIdTagError 
  extends IllegalArgumentException( "aggregateIdTag must be defined for Module" ) with DemesneModuleError

  final case object UndefinedAggregateRootPropsError
  extends IllegalArgumentException( "aggregate root actor props must be specificed for Module" ) with DemesneModuleError
}
