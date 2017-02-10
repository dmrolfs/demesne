package demesne.scaladsl

import akka.actor.Actor.Receive
import scalaz.Functor
import scalaz.Free._
import omnibus.commons.identifier._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.AggregateRootProps


trait SimpleAggregateModuleBuilder[A] extends AggregateModuleBuilder[A] {
  import SimpleAggregateModuleBuilder._

  def setIndexes( indexes: List[IndexSpecification] ): ModuleBuilderOp[Unit] = liftF( SetIndexes( indexes, () ) )

  def addIndex( index: IndexSpecification ): ModuleBuilderOp[Unit] = liftF( AddIndex( index, () ) )
}

object SimpleAggregateModuleBuilder {
  def apply[A]: SimpleAggregateModuleBuilder[A] = new SimpleAggregateModuleBuilder[A] { }

  //DMR: normally this would be defined within SimpleModuleBuilderOpF, but moved to here in order to support DRY structure

  implicit val functor: Functor[ModuleBuilderOpF] = new Functor[ModuleBuilderOpF] {
    override def map[A, B]( action: ModuleBuilderOpF[A] )( f: A => B ): ModuleBuilderOpF[B] = {
      action match {
        case SetIdTag( idTag, next ) => SetIdTag( idTag, f(next) )
        case SetProps( props, next ) => SetProps( props, f(next) )
        case SetAcceptance( acceptance, next ) => SetAcceptance( acceptance, f(next) )
        case Build( onBuild ) => Build( onBuild andThen f )
        case SetIndexes( indexes, next ) => SetIndexes( indexes, f(next) )
        case AddIndex( index, next ) => AddIndex( index, f(next) )
      }
    }
  }
}
