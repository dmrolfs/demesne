package demesne.index

import scala.reflect.ClassTag
import akka.actor.{ActorPath, Props}
import demesne.AggregateRootType


sealed trait RelaySubscription

import scala.language.existentials
case class ContextChannelSubscription( channel: Class[_] ) extends RelaySubscription

case object IndexBusSubscription extends RelaySubscription


abstract class AggregateIndexSpec[K: ClassTag, I: ClassTag, V: ClassTag] extends Equals {
  val keyTag: ClassTag[K] = implicitly[ClassTag[K]]
  val idTag: ClassTag[I] = implicitly[ClassTag[I]]
  val valueTag: ClassTag[V] = implicitly[ClassTag[V]]

  def name: Symbol
  def keyIdExtractor: KeyIdExtractor
  def agentProps( rootType: AggregateRootType ): Props
  def relaySubscription: RelaySubscription = IndexBusSubscription

  def topic( rootType: AggregateRootType ): String = makeTopic( name.name, rootType )( keyTag, idTag )

  def aggregateProps( rootType: AggregateRootType ): Props = IndexAggregate.props[K, I, V]( topic( rootType ) )
  def relayProps( aggregatePath: ActorPath ): Props = IndexRelay.props( aggregatePath, keyIdExtractor )
  def relayClassifier( rootType: AggregateRootType ): String = rootType.name

  override def hashCode: Int = {
    41 * (
      41 * (
        41 * (
          41 + name.##
        ) + keyTag.##
      ) + idTag.##
    ) + valueTag.##
  }

  override def equals( rhs: Any ): Boolean = rhs match {
    case that: AggregateIndexSpec[K, I, V] => {
      if ( this eq that ) true
      else {
        ( that.## == this.## ) &&
        ( that canEqual this ) &&
        ( that.name == this.name ) &&
        ( that.keyTag == this.keyTag ) &&
        ( that.idTag == this.idTag ) &&
        ( that.valueTag == this.valueTag )
      }
    }

    case _ => false
  }

  override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[AggregateIndexSpec[K, I, V]]

  override def toString: String = s"AggregateIndexSpec[${keyTag.toString}, ${idTag.toString}](${name.name})"
}
