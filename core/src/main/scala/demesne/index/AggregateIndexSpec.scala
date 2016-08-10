package demesne.index

import scala.reflect.ClassTag
import akka.actor.{ActorPath, Props}
import demesne.AggregateRootType


sealed trait RelaySubscription

import scala.language.existentials
case class ContextChannelSubscription( channel: Class[_] ) extends RelaySubscription

case object IndexBusSubscription extends RelaySubscription


abstract class AggregateIndexSpec[K, I]( implicit val keyTag: ClassTag[K], val idTag: ClassTag[I] ) extends Equals {
  def name: Symbol
  def keyIdExtractor: KeyIdExtractor
  def agentProps( rootType: AggregateRootType ): Props
  def relaySubscription: RelaySubscription = IndexBusSubscription

  def topic( rootType: AggregateRootType ): String = makeTopic( name.name, rootType )( keyTag, idTag )

  def aggregateProps( rootType: AggregateRootType ): Props = IndexAggregate.props[K, I]( topic( rootType ) )
  def relayProps( aggregatePath: ActorPath ): Props = IndexRelay.props( aggregatePath, keyIdExtractor )
  def relayClassifier( rootType: AggregateRootType ): String = rootType.name

  override def hashCode: Int = {
    41 * (
      41 * (
        41 + name.##
      ) + keyTag.##
    ) + idTag.##
  }

  override def equals( rhs: Any ): Boolean = rhs match {
    case that: AggregateIndexSpec[K, I] => {
      if ( this eq that ) true
      else {
        ( that.## == this.## ) &&
        ( that canEqual this ) &&
        ( that.name == this.name ) &&
        ( that.keyTag == this.keyTag ) &&
        ( that.idTag == this.idTag )
      }
    }

    case _ => false
  }

  override def canEqual( rhs: Any ): Boolean = rhs.isInstanceOf[AggregateIndexSpec[K, I]]

  override def toString: String = s"AggregateIndexSpec[${keyTag.toString}, ${idTag.toString}](${name.name})"
}
