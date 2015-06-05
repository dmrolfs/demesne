package demesne.register


object Directive {
  case class Record[K, I]( key: K, identifier: I ) extends Directive

  case class Withdraw[I]( identifier: I ) extends Directive

  case class Revise[K]( oldKey: K, newKey: K ) extends Directive
}

sealed trait Directive
