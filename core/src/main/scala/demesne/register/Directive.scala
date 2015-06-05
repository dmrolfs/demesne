package demesne.register



object Directive {
  /** Record directive tells the register to add the key:identifier pair to the register index.
   */
  case class Record[K, I]( key: K, identifier: I ) extends Directive

  /** Withdraw directive tells the register to remove the identifier from the register index.
   */
  case class Withdraw[I]( identifier: I ) extends Directive

  /** Revise directive tells the register change the index key.
   */
  case class Revise[K]( oldKey: K, newKey: K ) extends Directive
}


/** Register Directives define how the index register should respond to business events.
 */
sealed trait Directive
