package demesne.index

import scala.reflect.ClassTag
import akka.actor.{ ActorPath, Props }
import demesne.AggregateRootType

sealed trait RelaySubscription

import scala.language.existentials
case class ContextChannelSubscription( channel: Class[_] ) extends RelaySubscription

case object IndexBusSubscription extends RelaySubscription

trait IndexSpecification {
  def name: Symbol
  def topic( rootType: AggregateRootType ): String
  def agentProps( rootType: AggregateRootType ): Props
  def aggregateProps( rootType: AggregateRootType ): Props
  def relayProps( aggregatePath: ActorPath ): Props
  def relayClassifier( rootType: AggregateRootType ): String
  def relaySubscription: RelaySubscription
}

abstract class CommonIndexSpecification[K: ClassTag, I: ClassTag, V: ClassTag]
    extends IndexSpecification
    with Equals {
  val keyTag: ClassTag[K] = implicitly[ClassTag[K]]
  val idTag: ClassTag[I] = implicitly[ClassTag[I]]
  val valueTag: ClassTag[V] = implicitly[ClassTag[V]]

  def keyIdExtractor: KeyIdExtractor
  override def relaySubscription: RelaySubscription = IndexBusSubscription

  override def topic( rootType: AggregateRootType ): String =
    makeTopic( name.name, rootType )( keyTag, idTag )

  override def aggregateProps( rootType: AggregateRootType ): Props =
    IndexAggregate.props[K, I, V]( topic( rootType ) )
  override def relayProps( aggregatePath: ActorPath ): Props =
    IndexRelay.props( aggregatePath, keyIdExtractor )
  override def relayClassifier( rootType: AggregateRootType ): String = rootType.name

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
    case that: CommonIndexSpecification[K, I, V] => {
      if (this eq that) true
      else {
        (that.## == this.##) &&
        (that canEqual this) &&
        (that.name == this.name) &&
        (that.keyTag == this.keyTag) &&
        (that.idTag == this.idTag) &&
        (that.valueTag == this.valueTag)
      }
    }

    case _ => false
  }

  override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[CommonIndexSpecification[K, I, V]]

  override def toString: String =
    s"CommonIndexSpecification[${keyTag.toString}, ${idTag.toString}](${name.name})"
}
