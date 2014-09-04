package demesne

import akka.actor.{ ActorContext, ActorLogging, ActorPath, ActorRef, ReceiveTimeout }
import akka.persistence.{ EventsourcedProcessor, SnapshotOffer }
import peds.akka.envelope._
import peds.commons.log.Trace
import peds.commons.util._


case class AggregateRootRef( rootType: AggregateRootType, id: Any, underlying: ActorRef ) extends Enveloping {
  val trace = Trace[AggregateRootRef]
  import AggregateRoot._

  def path: ActorPath = underlying.path
  override def pathname: String = path.name

  def tell( message: Any, sender: ActorRef ): Unit = trace.block( s"tell(${message}, ${sender})" ) { 
    underlying.send( message )( sender )
  }

  final def !( message: Any )( implicit sender: ActorRef = ActorRef.noSender ): Unit = trace.block( s"!(${message})(${sender})" ) { tell( message, sender ) }

  def forward( message: Any )( implicit context: ActorContext ): Unit = trace.block( s"forward(${message})" ) {
    underlying sendForward message
  }
}
