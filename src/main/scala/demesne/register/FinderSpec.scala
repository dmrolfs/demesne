package demesne.register

import akka.actor.{ActorPath, Props}
import demesne.AggregateRootType
import peds.commons.util._

import scala.language.existentials
import scala.reflect.ClassTag


sealed trait RelaySubscription
case class ContextChannelSubscription( channel: Class[_] ) extends RelaySubscription
case object RegisterBusSubscription extends RelaySubscription


abstract class FinderSpec[K: ClassTag, I: ClassTag] extends Equals {
  def name: Symbol
  def keyIdExtractor: KeyIdExtractor[K, I]
  def agentProps( rootType: AggregateRootType ): Props
  def relaySubscription: RelaySubscription = RegisterBusSubscription

  def key: Class[_] = implicitly[ClassTag[K]].runtimeClass
  def id: Class[_] = implicitly[ClassTag[I]].runtimeClass
  def topic( rootType: AggregateRootType ): String = makeTopic( name.name, rootType, key, id )

  def aggregateProps( rootType: AggregateRootType ): Props = RegisterAggregate.props[K, I]( topic( rootType ) )
  def relayProps( aggregatePath: ActorPath ): Props = RegisterRelay.props[K, I]( aggregatePath, keyIdExtractor )
  def relayClassifier( rootType: AggregateRootType ): String = rootType.name

  override def hashCode: Int = {
    41 * (
      41 * (
        41 + name.##
      ) + key.##
    ) + id.##
  }

  override def equals( rhs: Any ): Boolean = rhs match {
    case that: FinderSpec[K, I] => {
      if ( this eq that ) true
      else {
        ( that.## == this.## ) &&
        ( that canEqual this ) &&
        ( that.name == this.name ) &&
        ( that.key == this.key ) &&
        ( that.id == this.id )
      }
    }

    case _ => false
  }

  override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[FinderSpec[K, I]]

  override def toString: String = {
    val kname = implicitly[ClassTag[K]].runtimeClass.safeSimpleName
    val iname = implicitly[ClassTag[I]].runtimeClass.safeSimpleName
    s"FinderSpec[$kname, $iname](${name.name})"
  }
}
