package demesne

import akka.actor.{ActorContext, ActorPath, Props}
import peds.commons.util._

import scala.reflect.ClassTag


/**
 * The register package defines a capability for maintaining a common registry for aggregate facilitating predefined 
 * LOGICAL actor lookups. 
 *
 * Registers are kept in sync (eventually consistent) with changes to aggregate actors; e.g., creation, modification (todo), deletion (todo).
 *
 * [[Register]] clients can use the instance to easily look up [[AggregateRoot]] identifiers based on logical keys or in bulk. 
 *
 * Registers are eventually consistent with events pertaining to their [[AggregateRoot]]s.
 * Events flow from an [[AggregateRoot]] event publishing to a RegisterRelay subscriber, who filters for events specified by the 
 * AggregateIndexSpec defined in the [[AggregateRootType]].indexes() sequence, and directs the event to the corresponding
 * [[RegisterAggregate]]. The RegisterAggregate records the logical key to identifier mapping and published a Recorded event, which
 * is picked up by local [[Register]]s, such as an in-memory [[RegisterLocalAgent]] or in the future a register backed by some 
 * other form of cache (perhaps Redis). 
 *
 * [[AggregateRoot]]s override the publish() operation to publish events to the Register Index. [[RegisterRelay]] actors listen
 * for events published either via the RegisterBus (default) or the ContextChannel subscription for a specific class. Aggregates
 * can publish to additional channels by adding to the publish chain.
 *
 * [[LogicalIndex]] subscriptions are registered by overriding the [[AggregateRootType]].logicalIndexes() operation. Each 
 * subscription is provided a partial function that is used to extract logical key to aggregate indentfier mapping from the
 * published event. Events that don't match both the publish subscription of key-id extractor are ignored by the index register 
 * mechanism.
 */
package object register {

  type KeyIdExtractor = PartialFunction[Any, Directive]
  

  import scala.language.existentials
  /**
   * Utility function to make a standard event bus topic for events pertaining to a specific aggregate instance.
   */
  def makeTopic( name: String, rootType: AggregateRootType, key: Class[_], id: Class[_] ): String = {
    s"${rootType.name}+${name}+${key.safeSimpleName}:${id.safeSimpleName}"
  }


  /**
   * base register protocol type
   */
  sealed trait RegisterMessage

  /**
   * marks the beginning of initializing the local register agent.
   */
  case object WaitingForStart extends RegisterMessage

  /**
   * notifies each local listener that the agent has completed initialization and is ready
   */
  case object Started extends RegisterMessage

  /**
   * request register agent to be used in subscriber
   */
  case object GetRegister extends RegisterMessage

  /**
   * envelope message used to deliver the register akka agent
   */
  case class RegisterEnvelope( payload: Any ) extends RegisterMessage {
    def mapTo[K, I]: Register[K, I] = payload.asInstanceOf[Register[K, I]]
  }
}
