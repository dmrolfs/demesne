package demesne

import akka.actor.{ActorContext, ActorPath, Props}
import peds.commons.util._

import scala.reflect.ClassTag


/**
 * Created by damonrolfs on 11/5/14.
 */
package object register {

  type KeyIdExtractor[K, I] = PartialFunction[Any, (K, I)]


  import scala.language.existentials
  def makeTopic( name: String, rootType: AggregateRootType, key: Class[_], id: Class[_] ): String = {
    s"${rootType.name}+${name}+${key.safeSimpleName}:${id.safeSimpleName}"
  }


  sealed trait RegisterMessage

  case object WaitingForStart extends RegisterMessage

  case object Started extends RegisterMessage

  case object GetRegister extends RegisterMessage

  case class RegisterEnvelope( payload: Any ) extends RegisterMessage {
    def mapTo[K, I]: Register[K, I] = payload.asInstanceOf[Register[K, I]]
  }
}
