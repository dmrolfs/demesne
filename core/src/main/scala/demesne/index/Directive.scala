package demesne.index

import omnibus.commons.util._


object Directive {
  /** Record directive tells the index to add the key:identifier pair to the index index.
   */
  case class Record[K, I, V]( key: K, identifier: I, value: V ) extends Directive {
    override def toString: String = {
      getClass.safeSimpleName + s"(${key}:${key.getClass.safeSimpleName}, ${identifier}:${identifier.getClass.safeSimpleName}, ${value}:${value.getClass.safeSimpleName})"
    }
  }

  object Record {
    def apply[K, I]( key: K, identifier: I ): Record[K, I, I] = Record( key = key, identifier = identifier, value = identifier )
  }

  /** Withdraw directive tells the index to remove the identifier from the index index.
   */
  case class Withdraw[K, I]( identifier: I, key: Option[K] = None ) extends Directive

  /** Revise directive tells the index change the index key.
   */
  case class ReviseKey[K]( oldKey: K, newKey: K ) extends Directive

  /** Revise directive tells the index change the index value.
    */
  case class ReviseValue[K, V]( key: K, oldValue: V, newValue: V ) extends Directive

  case class AlterValue[K, V]( key: K )( val alter: V => V ) extends Directive

  /** Tells the relay to take no action for this case.
   */
  case object Ignore extends Directive
}


/** Index Directives define how the index index should respond to business events.
 */
sealed trait Directive
