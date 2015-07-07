package demesne.scaladsl

import akka.actor.Actor.Receive
import scalaz.Free._
import peds.commons.identifier._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.AggregateRootProps
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModuleBuilder[A] {
  def setIdTag( newIdTag: Symbol ): SimpleModuleBuilderOp[Unit] = liftF( SetIdTag( newIdTag, () ) )

  def addIndex( index: AggregateIndexSpec[_, _] ): SimpleModuleBuilderOp[Unit] = liftF( AddIndex( index, () ) )

  def setProps( props: AggregateRootProps ): SimpleModuleBuilderOp[Unit] = liftF( SetProps( props, () ) )

  def setAcceptance[S]( newAcceptance: AggregateRoot.Acceptance[S] ): SimpleModuleBuilderOp[Unit] = {
    liftF( SetAcceptance( newAcceptance, () ) )
  }

  def build: SimpleModuleBuilderOp[AggregateRootModule] = liftF( Build( identity ) )

}
