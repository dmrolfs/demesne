package demesne.scaladsl

import akka.actor.Actor.Receive
import scalaz.Free._
import shapeless.Lens
import peds.archetype.domain.model.core.Entity
import peds.commons.identifier._
import demesne.{ AggregateRootModule, AggregateStateSpecification }
import demesne.register.AggregateIndexSpec


trait ModuleBuilder[E <: Entity] {
  def setIdTag( newIdTag: Symbol ): ModuleBuilderOp[Unit] = liftF( SetIdTag( newIdTag, () ) )

  def setIdLens( lens: Lens[E, TaggedID[ShortUUID]] ): ModuleBuilderOp[Unit] = liftF( SetIdLens( lens, () ) )

  def setNameLens( lens: Lens[E, String] ): ModuleBuilderOp[Unit] = liftF( SetNameLens( lens, () ) )

  def setAcceptance[S]( newAcceptance: AggregateStateSpecification.Acceptance[S] ): ModuleBuilderOp[Unit] = {
    liftF( SetAcceptance( newAcceptance, () ) )
  }

  def addIndex( index: AggregateIndexSpec[_, _] ): ModuleBuilderOp[Unit] = liftF( AddIndex( index, () ) )

  def setReceiveCommand( newReceive: Receive ): ModuleBuilderOp[Unit] = liftF( SetReceiveCommand( newReceive, () ) )

  def build: ModuleBuilderOp[AggregateRootModule] = liftF( Build( identity ) )
}
