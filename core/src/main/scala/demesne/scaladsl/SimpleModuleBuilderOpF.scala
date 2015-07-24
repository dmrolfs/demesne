package demesne.scaladsl

import scala.language.existentials
import demesne.register.AggregateIndexSpec


trait SimpleModuleBuilderOpF[+A] extends ModuleBuilderOpF[A]

case class SetIndexes[+A]( indexes: List[AggregateIndexSpec[_, _]], next: A ) extends SimpleModuleBuilderOpF[A]

case class AddIndex[+A]( index: AggregateIndexSpec[_, _], next: A ) extends SimpleModuleBuilderOpF[A]
