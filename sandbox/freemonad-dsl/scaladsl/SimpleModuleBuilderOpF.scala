package demesne.scaladsl

import scala.language.existentials


trait SimpleModuleBuilderOpF[+A] extends ModuleBuilderOpF[A]

case class SetIndexes[+A]( indexes: List[IndexSpecification], next: A ) extends SimpleModuleBuilderOpF[A]

case class AddIndex[+A]( index: IndexSpecification, next: A ) extends SimpleModuleBuilderOpF[A]
