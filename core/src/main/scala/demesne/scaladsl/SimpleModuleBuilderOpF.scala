package demesne.scaladsl

import scala.language.existentials
import akka.actor.Actor.Receive
import scalaz._, Scalaz._
import scalaz.Functor
import peds.commons.identifier._
import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.AggregateRootProps
import demesne.register.AggregateIndexSpec


sealed trait SimpleModuleBuilderOpF[+A]

case class SetIdTag[+A]( idTag: Symbol, next: A ) extends SimpleModuleBuilderOpF[A]

case class SetIndexes[+A]( indexes: List[AggregateIndexSpec[_, _]], next: A ) extends SimpleModuleBuilderOpF[A]

case class AddIndex[+A]( index: AggregateIndexSpec[_, _], next: A ) extends SimpleModuleBuilderOpF[A]

case class SetProps[+A]( props: AggregateRootProps, next: A ) extends SimpleModuleBuilderOpF[A]

case class SetAcceptance[+A, S]( acceptance: AggregateRoot.Acceptance[S], next: A ) extends SimpleModuleBuilderOpF[A]

case class Build[+A]( onBuild: AggregateRootModule => A ) extends SimpleModuleBuilderOpF[A]

object SimpleModuleBuilderOpF {
  implicit val functor: Functor[SimpleModuleBuilderOpF] = new Functor[SimpleModuleBuilderOpF] {
    def map[A, B]( action: SimpleModuleBuilderOpF[A] )( f: A => B ): SimpleModuleBuilderOpF[B] = {
      action match {
        case SetIdTag( idTag, next ) => SetIdTag( idTag, f(next) )
        case SetIndexes( indexes, next ) => SetIndexes( indexes, f(next) )
        case AddIndex( index, next ) => AddIndex( index, f(next) )
        case SetProps( props, next ) => SetProps( props, f(next) )
        case SetAcceptance( acceptance, next ) => SetAcceptance( acceptance, f(next) )
        case Build( onBuild ) => Build( onBuild andThen f )
      }
    }
  }
}
