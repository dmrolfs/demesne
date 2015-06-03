package demesne

import akka.actor.{ActorContext, ActorPath, Props}
import peds.commons.util._

import scala.reflect.ClassTag


/**
 * The register package defines a capability for maintaining a common registry for aggregate facilitating predefined 
 * LOGICAL actor lookups. 
 *
 * Registers are kept in sync (eventually consistent) with changes to aggregate actors; e.g., creation, modification, deletion.
 */
package object register {

  type KeyIdExtractor[K, I] = PartialFunction[Any, (K, I)]


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
