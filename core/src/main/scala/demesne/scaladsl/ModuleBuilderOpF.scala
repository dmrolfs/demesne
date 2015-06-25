package demesne.scaladsl

import scala.language.existentials
import akka.actor.Actor.Receive
import scalaz.{ Lens => _, _ }, Scalaz._
import shapeless.Lens
import scalaz.Functor
import peds.archetype.domain.model.core.Entity
import peds.commons.identifier._
import demesne.{ AggregateRootModule, AggregateStateSpecification }
import demesne.register.AggregateIndexSpec


sealed trait ModuleBuilderOpF[+A]

case class SetIdTag[+A]( idTag: Symbol, next: A ) extends ModuleBuilderOpF[A]

case class SetIdLens[+A]( lens: Lens[_, _], next: A ) extends ModuleBuilderOpF[A]

case class SetNameLens[+A]( lens: Lens[_, _], next: A ) extends ModuleBuilderOpF[A]

case class SetAcceptance[+A, S]( acceptance: AggregateStateSpecification.Acceptance[S], next: A ) extends ModuleBuilderOpF[A]

case class AddIndex[+A]( index: AggregateIndexSpec[_, _], next: A ) extends ModuleBuilderOpF[A]

case class SetReceiveCommand[+A]( receive: Receive, next: A ) extends ModuleBuilderOpF[A]

case class Build[+A]( onBuild: AggregateRootModule => A ) extends ModuleBuilderOpF[A]

object ModuleBuilderOpF {
  implicit val functor: Functor[ModuleBuilderOpF] = new Functor[ModuleBuilderOpF] {
    def map[A, B]( action: ModuleBuilderOpF[A] )( f: A => B ): ModuleBuilderOpF[B] = {
      action match {
        case SetIdTag( idTag, next ) => SetIdTag( idTag, f(next) )
        case SetIdLens( lens, next ) => SetIdLens( lens, f(next) )
        case SetNameLens( lens, next ) => SetNameLens( lens, f(next) )
        case SetAcceptance( acceptance, next ) => SetAcceptance( acceptance, f(next) )
        case AddIndex( index, next ) => AddIndex( index, f(next) )
        case SetReceiveCommand( receive, next ) => SetReceiveCommand( receive, f(next) )
        case Build( onBuild ) => Build( onBuild andThen f )
      }
    }
  }
}
