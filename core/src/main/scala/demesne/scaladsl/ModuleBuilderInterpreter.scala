package demesne.scaladsl

import scala.reflect.ClassTag
import akka.actor.Actor
import akka.actor.Actor.Receive
import scalaz.{ Lens => _, _ }, Scalaz._
import scalaz.concurrent.Task
import shapeless.Lens
import Task._
import peds.archetype.domain.model.core.Entity
import peds.commons.V
import peds.commons.log.Trace
import demesne.{ AggregateRootModule, AggregateStateSpecification }
import demesne.module.{ BasicEntityModule, DemesneModuleError }
import demesne.register.AggregateIndexSpec
import demesne.AggregateStateSpecification.Acceptance


trait ModuleBuilderInterpreter {
  def apply[A]( action: ModuleBuilderOp[A] ): Task[A]
}

case class BasicModuleBuilderInterpreter[E <: Entity : ClassTag]() extends ModuleBuilderInterpreter {
  import BasicModuleBuilderInterpreter._

  type ActorState = BasicEntityModule[E]#ActorState
  type Command = AggregateRootModule#Command

  case class BasicModule(
    idTagO: Option[Symbol] = None,
    idLensO: Option[Lens[E, peds.commons.identifier.TaggedID[peds.commons.identifier.ShortUUID]]] = None,
    nameLensO: Option[Lens[E, String]] = None,
    override val indexes: Seq[AggregateIndexSpec[_, _]] = Seq(),
    override val acceptance: AggregateStateSpecification.Acceptance[ActorState] = BasicModule.emptyAcceptance,
    eventMap: ActorState => PartialFunction[Command, Any] = BasicModule.emptyEventMap( _ ),
    override val activeCommand: Receive = Actor.emptyBehavior
  ) extends BasicEntityModule[E] {
    override def trace: Trace[_] = Trace[BasicModule]
    override val evEntity: ClassTag[E] = implicitly[ClassTag[E]]
    // override def idLens: Lens[BasicEntity, TID] = ??? //idLensO getOrElse { throw BasicModule.UndefinedLensError( "idLens" ) }
    // override def nameLens: Lens[BasicEntity, String] = ??? //nameLensO getOrElse { throw BasicModule.UndefinedLensError( "nameLens" ) }
override def idLens: shapeless.Lens[E, peds.commons.identifier.TaggedID[peds.commons.identifier.ShortUUID]] = idLensO getOrElse { throw UndefinedLensError( "idLens" ) }
override def nameLens: shapeless.Lens[E, String] = nameLensO getOrElse { throw UndefinedLensError( "nameLens" ) }
    override def aggregateIdTag: Symbol = idTagO getOrElse { throw UndefinedAggregateIdTagError }
    override def eventFor( state: ActorState ): PartialFunction[Command, Any] = eventMap( state )
  }

  object BasicModule {
    def emptyEventMap( state: ActorState ): PartialFunction[Command, Any] = {
      peds.commons.util.emptyBehavior[Command, Any]
    }

    def emptyAcceptance: Acceptance[ActorState] = peds.commons.util.emptyBehavior[(Any, ActorState), ActorState]
  }


  var module: BasicModule = BasicModule()

  // def step[A]( action: ModuleBuilderOpF[ModuleBuilderOp[A]] ): \/[Throwable, ModuleBuilderOp[A]] = {
  def step[A]( action: ModuleBuilderOpF[ModuleBuilderOp[A]] ): Task[ModuleBuilderOp[A]] = {
    // implicit val evAS = module.evEntity
    // implicit val evAcceptance = scala.reflect.ClassTag( classOf[Acceptance[BasicModuleBuilderInterpreter[E]#ActorState]] )

    action match {
      case SetIdTag( newIdTag, next ) => {
        module = module.copy( idTagO = Some(newIdTag) )
        // next.right[Throwable]
        now( next )
      }

      case SetIdLens( lens, next ) => {
        module = module.copy( idLensO = Some(lens.asInstanceOf[Lens[E, peds.commons.identifier.TaggedID[peds.commons.identifier.ShortUUID]]]) )
        now( next )
      }

      case SetNameLens( lens, next ) => {
        module = module.copy( nameLensO = Some(lens.asInstanceOf[Lens[E, String]]) )
        now( next )
      }

      case SetAcceptance( newAcceptance, next ) => {
        val result = scala.util.Try {
          module = module.copy( acceptance = newAcceptance.asInstanceOf[Acceptance[ActorState]] )
        }

        result match {
          // case Success(_) => next.right[Throwable]
          // case Failure( ex ) => ex.left[ModuleBuilderOp[A]]
          case scala.util.Success(_) => now( next )
          case scala.util.Failure( ex ) => fail( ex )
        }
      }

      case AddIndex( index, next ) => {
        module = module.copy( indexes = module.indexes :+ index )
        // next.right[Throwable]
        now( next )
      }

      case SetReceiveCommand( newReceive, next ) => {
        module = module.copy( activeCommand = newReceive )
        // next.right[Throwable]
        now( next )
      }

      case Build( onBuild ) => {
        val result = for {
          _ <- checkIdTag( module.idTagO )
          _ <- checkIdLens( module.idLensO )
          _ <- checkNameLens( module.nameLensO )
        } yield module

        result match {
          case \/-(m) => now( onBuild(m) )
          case -\/(ex) => fail( ex )
        }
        // module.idTag match {
        //   // case Some(_) => onBuild( module ).right[Throwable]
        //   // case None => BasicModule.UndefinedAggregateIdTagError.left[ModuleBuilderOp[A]]
        //   case Some(_) => now( onBuild(module) )
        //   case None => fail( UndefinedAggregateIdTagError )
        // }
      }
    }
  }

  def checkIdTag( tag: Option[Symbol] ): \/[DemesneModuleError, Symbol] = {
    tag map { _.right } getOrElse { UndefinedAggregateIdTagError.left }
  }

  def checkIdLens( 
    lens: Option[Lens[E, peds.commons.identifier.TaggedID[peds.commons.identifier.ShortUUID]]] 
  ): \/[DemesneModuleError, Lens[E, peds.commons.identifier.TaggedID[peds.commons.identifier.ShortUUID]]] = {
    lens map { _.right } getOrElse { UndefinedLensError( "idLens" ).left }
  }

  def checkNameLens( lens: Option[Lens[E, String]] ): \/[DemesneModuleError, Lens[E, String]] = {
    lens map { _.right } getOrElse { UndefinedLensError( "nameLens" ).left }
  }

  // def apply[A]( action: ModuleBuilderOp[A] ): \/[Throwable, A] = {
  //   implicit val monadBuilder = implicitly[Monad[ModuleBuilderOp]]
  override def apply[A]( action: ModuleBuilderOp[A] ): Task[A] = action.runM( step )
}

object BasicModuleBuilderInterpreter {
  final case object UndefinedAggregateIdTagError 
  extends IllegalArgumentException( "aggregateIdTag must be defined for Module" ) with DemesneModuleError

  final case class UndefinedLensError( lens: String ) 
  extends IllegalArgumentException( "$lens must be defined for Module") with DemesneModuleError

}