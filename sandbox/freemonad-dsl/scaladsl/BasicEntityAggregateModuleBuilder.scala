package demesne.scaladsl

import scalaz.Functor
import scalaz.Free._
import shapeless.Lens
import peds.archetype.domain.model.core.Entity


trait BasicEntityAggregateModuleBuilder[A] extends SimpleAggregateModuleBuilder[A] {
  import BasicEntityAggregateModuleBuilder._

  def setIdLens( idLens: Lens[_, _] ): ModuleBuilderOp[Unit] = liftF( SetIdLens[Unit]( idLens, () ) ) // Lens[A, A#TID]
  def setNameLens( nameLens: Lens[_, String] ): ModuleBuilderOp[Unit] = liftF( SetNameLens[Unit]( nameLens, () ) ) // Lens[A, String]
  def setSlugLens( slugLens: Lens[_, String] ): ModuleBuilderOp[Unit] = liftF( SetNameLens[Unit]( slugLens, () ) ) // Lens[A, String]
  def setIsActiveLens( isActiveLens: Lens[_, Boolean] ): ModuleBuilderOp[Unit] = liftF( SetIsActiveLens[Unit]( isActiveLens, () ) ) // Lens[A, Boolean]
}

object BasicEntityAggregateModuleBuilder {
  def apply[A]: BasicEntityAggregateModuleBuilder[A] = new BasicEntityAggregateModuleBuilder[A] { }
  
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
        case SetIdLens( idLens, next ) => SetIdLens( idLens, f(next) )
        case SetNameLens( nameLens, next ) => SetNameLens( nameLens, f(next) )
        case SetSlugLens( slugLens, next ) => SetSlugLens( slugLens, f(next) )
        case SetIsActiveLens( isActiveLens, next ) => SetIsActiveLens( isActiveLens, f(next) )
      }
    }
  }
}