package demesne.scaladsl

import demesne.{ AggregateRoot, AggregateRootModule }
import demesne.module.AggregateRootProps
import demesne.index.AggregateIndexSpec


trait ModuleBuilderOpF[+A]

case class SetIdTag[+A]( idTag: Symbol, next: A ) extends ModuleBuilderOpF[A]

case class SetProps[+A]( props: AggregateRootProps, next: A ) extends ModuleBuilderOpF[A]

case class SetAcceptance[+A, S]( acceptance: AggregateRoot.Acceptance[S], next: A ) extends ModuleBuilderOpF[A]

case class Build[+A]( onBuild: AggregateRootModule => A ) extends ModuleBuilderOpF[A]
