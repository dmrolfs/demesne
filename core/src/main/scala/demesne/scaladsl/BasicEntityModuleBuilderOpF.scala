package demesne.scaladsl

import shapeless.Lens
import peds.archetype.domain.model.core.Entity


trait BasicEntityModuleBuilderOpF[+A] extends SimpleModuleBuilderOpF[A]

case class SetIdLens[+A, E <: Entity]( idLens: Lens[E, E#TID], next: A ) extends BasicEntityModuleBuilderOpF[A]

case class SetNameLens[+A, E <: Entity]( nameLens: Lens[E, String], next: A ) extends BasicEntityModuleBuilderOpF[A]

case class SetSlugLens[+A, E <: Entity]( slugLens: Lens[E, String], next: A ) extends BasicEntityModuleBuilderOpF[A]

case class SetIsActiveLens[+A, E <: Entity]( isActiveLens: Lens[E, Boolean], next: A ) extends BasicEntityModuleBuilderOpF[A]
