package demesne.scaladsl

import akka.actor.Actor.Receive
import scalaz.Functor
import scalaz.Free._
import peds.commons.identifier._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.AggregateRootProps
import demesne.register.AggregateIndexSpec


trait AggregateModuleBuilder[A] {
  import AggregateModuleBuilder._
  
  def setIdTag( newIdTag: Symbol ): ModuleBuilderOp[Unit] = liftF( SetIdTag( newIdTag, () ) )

  def setProps( props: AggregateRootProps ): ModuleBuilderOp[Unit] = liftF( SetProps( props, () ) )

  def build: ModuleBuilderOp[AggregateRootModule] = liftF( Build( identity ) )
}

object AggregateModuleBuilder {
  //DMR: normally this would be defined within SimpleModuleBuilderOpF, but moved to here in order to support DRY structure

  implicit val functor: Functor[ModuleBuilderOpF] = new Functor[ModuleBuilderOpF] {
    override def map[A, B]( action: ModuleBuilderOpF[A] )( f: A => B ): ModuleBuilderOpF[B] = {
      action match {
        case SetIdTag( idTag, next ) => SetIdTag( idTag, f(next) )
        case SetProps( props, next ) => SetProps( props, f(next) )
        case SetAcceptance( acceptance, next ) => SetAcceptance( acceptance, f(next) )
        case Build( onBuild ) => Build( onBuild andThen f )
      }
    }
  }
}
