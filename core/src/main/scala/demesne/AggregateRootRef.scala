//package demesne
//
//import akka.actor.{ActorContext, ActorPath, ActorRef, ActorSystem}
//import peds.akka.envelope._
//import peds.commons.log.Trace
//
//
////todo: DO I really need this class? roottype and id aren't used anywhere?  why not return ActorRef from DomainModel
////todo: since enveloping handles message wrap
//todo: the problem stems from not being able to extend ActorRef -- it's methods are final to akka.
//case class AggregateRootRef(
//  underlying: ActorRef,
//  rootType: AggregateRootType,
//  id: Any
//) extends ActorRef with Enveloping {
//  val trace = Trace[AggregateRootRef]
//  def path: ActorPath = underlying.path
//  override def pathname: String = path.name
//
//  override def tell( message: Any, sender: ActorRef ): Unit = trace.block( s"tell(${message}, ${sender})" ) { underlying.send( message )( sender ) }
////  final def !( message: Any )( implicit sender: ActorRef = ActorRef.noSender ): Unit = tell( message, sender )
//  override def forward( message: Any )( implicit context: ActorContext ): Unit = trace.block( s"forward(${message})" ) { underlying sendForward message }
//}
//
//object AggregateRootRef {
//  def deadLetters( implicit system: ActorSystem ): AggregateRootRef = AggregateRootRef( null, null, system.deadLetters )
//}