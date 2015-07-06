package demesne.scaladsl

import akka.actor.Actor.Receive
import scalaz.Free._
import peds.commons.identifier._
import demesne.{ AggregateRootModule, AggregateStateSpecification }
import demesne.module.StatefulReceive
import demesne.register.AggregateIndexSpec


trait SimpleAggregateModuleBuilder[A] {
  def setIdTag( newIdTag: Symbol ): SimpleModuleBuilderOp[Unit] = liftF( SetIdTag( newIdTag, () ) )

  def setAcceptance[S]( newAcceptance: AggregateStateSpecification.Acceptance[S] ): SimpleModuleBuilderOp[Unit] = {
    liftF( SetAcceptance( newAcceptance, () ) )
  }

  def addIndex( index: AggregateIndexSpec[_, _] ): SimpleModuleBuilderOp[Unit] = liftF( AddIndex( index, () ) )

  def setReceiveCommand( newReceive: StatefulReceive[A] ): SimpleModuleBuilderOp[Unit] = liftF( SetReceiveCommand( newReceive, () ) )

  def build: SimpleModuleBuilderOp[AggregateRootModule] = liftF( Build( identity ) )
}
