package demesne

import akka.actor.{ActorSystem, ActorContext, ActorPath, ActorRef}
import peds.akka.envelope._


case class AggregateRootRef( rootType: AggregateRootType, id: Any, underlying: ActorRef ) extends Enveloping {
  def path: ActorPath = underlying.path
  override def pathname: String = path.name

  def tell( message: Any, sender: ActorRef ): Unit = underlying.send( message )( sender )
  final def !( message: Any )( implicit sender: ActorRef = ActorRef.noSender ): Unit = tell( message, sender )
  def forward( message: Any )( implicit context: ActorContext ): Unit = underlying sendForward message
}

object AggregateRootRef {
  def deadLetters( implicit system: ActorSystem ): AggregateRootRef = AggregateRootRef( null, null, system.deadLetters )
}