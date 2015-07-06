package demesne.scaladsl

import scala.reflect.ClassTag
import akka.actor.Actor
import akka.actor.Actor.Receive
import scalaz._, Scalaz._
import peds.commons.V
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{ AggregateRootModule, AggregateStateSpecification }
import demesne.module.{ DemesneModuleError, SimpleAggregateModule }
import demesne.register.AggregateIndexSpec


trait ModuleBuilderInterpreter {
  def apply[A]( action: SimpleModuleBuilderOp[A] ): Id[A]
}

case class SimpleAggregateModuleBuilderInterpreter[A: ClassTag]() extends ModuleBuilderInterpreter {
  import SimpleAggregateModuleBuilderInterpreter._

  type Acceptance = demesne.AggregateStateSpecification.Acceptance[A]
  type StatefulReceive = demesne.module.StatefulReceive[A]


  case class SimpleModule(
    idTagO: Option[Symbol] = None,
    override val indexes: Seq[AggregateIndexSpec[_, _]] = Seq(),
    override val acceptance: Acceptance = SimpleModule.emptyAcceptance,
    statefulReceiveO: Option[StatefulReceive] = None
  ) extends SimpleAggregateModule[A] {
    override def trace: Trace[_] = Trace[SimpleModule]
    override val evAggregate: ClassTag[A] = implicitly[ClassTag[A]]
    override def aggregateIdTag: Symbol = idTagO getOrElse { throw UndefinedAggregateIdTagError }
    override def statefulReceiveCommand: StatefulReceive = statefulReceiveO getOrElse { super.statefulReceiveCommand }

    override def toString: String = {
      s"${getClass.safeSimpleName}[${aggregateClass.safeSimpleName}](${aggregateIdTag} " +
      s"""indexes=${indexes.map(_.name).mkString("[", ",", "]")})"""
    }
  }

  object SimpleModule {
    def emptyAcceptance: Acceptance = peds.commons.util.emptyBehavior[(Any, A), A]
  }


  var module: SimpleModule = SimpleModule()

  def step[A]( action: SimpleModuleBuilderOpF[SimpleModuleBuilderOp[A]] ): Id[SimpleModuleBuilderOp[A]] = {
    action match {
      case SetIdTag( newIdTag, next ) => {
        module = module.copy( idTagO = Some(newIdTag) )
        next
      }

      case SetAcceptance( newAcceptance, next ) => {
        val result = scala.util.Try {
          module = module.copy( acceptance = newAcceptance.asInstanceOf[Acceptance] )
        }

        result match {
          case scala.util.Success(_) => next
          case scala.util.Failure( ex ) => throw ex
        }
      }

      case AddIndex( index, next ) => {
        module = module.copy( indexes = module.indexes :+ index )
        next
      }

      case SetReceiveCommand( newReceive, next ) => {
        module = module.copy( statefulReceiveO = Some(newReceive.asInstanceOf[StatefulReceive]) )
        next
      }

      case Build( onBuild ) => {
        checkIdTag( module.idTagO ) map { _ => module } match {
          case \/-( m ) => onBuild( m )
          case -\/( ex ) => throw ex
        }
      }
    }
  }

  def checkIdTag( tag: Option[Symbol] ): \/[DemesneModuleError, Symbol] = {
    tag map { _.right } getOrElse { UndefinedAggregateIdTagError.left }
  }

  override def apply[A]( action: SimpleModuleBuilderOp[A] ): Id[A] = action.runM( step )
}

object SimpleAggregateModuleBuilderInterpreter {
  final case object UndefinedAggregateIdTagError 
  extends IllegalArgumentException( "aggregateIdTag must be defined for Module" ) with DemesneModuleError
}