package demesne.register

import akka.actor.{ActorPath, Props}
import demesne.AggregateRootType

import scala.language.existentials
import scala.reflect.ClassTag


sealed trait RelaySubscription
case class ContextChannelSubscription( channel: Class[_] ) extends RelaySubscription
case object RegisterBusSubscription extends RelaySubscription


abstract class FinderSpec[K: ClassTag, I: ClassTag] {
  def name: Symbol
  def rootType: AggregateRootType
  def keyIdExtractor: KeyIdExtractor[K, I]
  def accessProps: Props
  def relaySubscription: RelaySubscription = RegisterBusSubscription

  def topic: String = makeTopic( name.name, rootType, key, id )
  def key: Class[_] = implicitly[ClassTag[K]].runtimeClass
  def id: Class[_] = implicitly[ClassTag[I]].runtimeClass
  def aggregateProps: Props = RegisterAggregate.props[K, I]( topic )
  def relayProps( registerPath: ActorPath ): Props = RegisterRelay.props[K, I]( registerPath, keyIdExtractor )
  def relayClassifier: String = rootType.name
}
