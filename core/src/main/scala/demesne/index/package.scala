package demesne

import scala.reflect.ClassTag


/**
 * The index index defines a capability for maintaining a common registry for aggregate facilitating predefined
 * LOGICAL actor lookups. 
 *
 * Indexes are kept in sync (eventually consistent) with changes to aggregate actors; e.g., creation, modification (todo),
 * deletion (todo).
 *
 * [[demesne.index.Index]] clients can use the instance to easily look up [[AggregateRoot]] identifiers based on logical keys or
 * in bulk.
 *
 * Indexes are eventually consistent with events pertaining to their [[AggregateRoot]]s.
 * Events flow from an [[AggregateRoot]] event publishing to a IndexRelay subscriber, who filters for events specified by the
 * [[demesne.index.IndexSpecification]] defined in the [[AggregateRootType]].indexes() sequence, and directs the event to the
 * corresponding [[demesne.index.IndexAggregate]]. The IndexAggregate records the logical key to identifier mapping and published
 * a Recorded event, which is picked up by local [[demesne.index.Index]]s, such as an in-memory
 * [[demesne.index.local.IndexLocalAgent]] or in the future a index backed by some other form of cache (perhaps Redis).
 *
 * [[AggregateRoot]]s override the publish() operation to publish events to the Index Index. [[demesne.index.IndexRelay]] actors
 * listen for events published either via the IndexBus (default) or the ContextChannel subscription for a specific class.
 * Aggregates can publish to additional channels by adding to the publish chain.
 *
 * [[demesne.index.Index]] subscriptions are registered by overriding the [[AggregateRootType]].indexes() operation. Each
 * subscription is provided a partial function that is used to extract logical key to aggregate indentfier mapping from the
 * published event. Events that don't match both the publish subscription of key-id extractor are ignored by the index index
 * mechanism.
 */
package object index {

  type KeyIdExtractor = PartialFunction[Any, Directive]
  

  import scala.language.existentials
  /**
   * Utility function to make a standard event bus topic for events pertaining to a specific aggregate instance.
   */
//  def makeTopic( name: String, rootType: AggregateRootType, key: Class[_], id: Class[_] ): String = {
  def makeTopic[K: ClassTag, I: ClassTag]( name: String, rootType: AggregateRootType ): String = {
    s"${rootType.name}+${name}+${implicitly[ClassTag[K]].toString}:${implicitly[ClassTag[I]].toString}"
  }
}
