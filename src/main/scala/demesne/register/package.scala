package demesne

import akka.actor.{ActorPath, Props}
import peds.commons.util._

import scala.reflect.ClassTag


/**
 * Created by damonrolfs on 11/5/14.
 */
package object register {
  import scala.language.existentials

  def makeTopic( name: String, rootType: AggregateRootType, key: Class[_], id: Class[_] ): String = {
    s"${rootType.name}+${name}+${key.safeSimpleName}:${id.safeSimpleName}"
  }

  type KeyIdExtractor[K, I] = PartialFunction[Any, (K, I)]


  abstract class FinderSpec[K: ClassTag, I: ClassTag] {
    def name: Symbol
    def rootType: AggregateRootType
    def keyIdExtractor: KeyIdExtractor[K, I]
    def accessProps: Props

    def topic: String = makeTopic( name.name, rootType, key, id )
    def key: Class[_] = implicitly[ClassTag[K]].runtimeClass
    def id: Class[_] = implicitly[ClassTag[I]].runtimeClass
    def aggregateProps: Props = RegisterAggregate.props[K, I]( topic )
    def relayProps( registerPath: ActorPath ): Props = RegisterRelay.props[K, I]( registerPath, keyIdExtractor )
  }


  sealed trait RegisterMessage
  case object GetRegister extends RegisterMessage
  case class RegisterEnvelope( payload: Any ) extends RegisterMessage {
    def mapTo[K, I]: Register[K, I] = payload.asInstanceOf[Register[K, I]]
  }
}
