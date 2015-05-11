package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scalaz._, Scalaz._
import scala.concurrent.{ Await, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import demesne.AggregateRootModule
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import peds.commons.log.Trace

import scala.reflect.ClassTag


object AggregateRootSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/17/14.
 */
abstract class AggregateRootSpec[A: ClassTag]
  extends SequentialAkkaSpecWithIsolatedFixture
  with MockitoSugar
  with BeforeAndAfterAll {

  private val trace = Trace[AggregateRootSpec[A]]

  abstract class AggregateFixture( id: Int = AggregateRootSpec.sysId.incrementAndGet() ) extends AkkaFixture {
    private val trace = Trace[AggregateFixture]

    import scala.concurrent.duration._
    import akka.util.Timeout
    implicit val timeout = Timeout( 5.seconds )

    def before(): Unit = trace.block( "before" ) { 
      for { 
        init <- moduleCompanions.map{ _ initialize context }.sequence
      } {
        Await.ready( Future sequence { init }, 2.seconds )
      }
    }

    def after(): Unit = trace.block( "after" ) { }

    def moduleCompanions: List[AggregateRootModule]

    def context: Map[Symbol, Any] = trace.block( "context()" ) {
      Map(
        demesne.ModelKey -> model,
        demesne.SystemKey -> system,
        demesne.FactoryKey -> demesne.factory.contextFactory
      )
    }
  }

  override type Fixture <: AggregateFixture

  override def withFixture( test: OneArgTest ): Outcome = trace.block( s"withFixture(${test}})" ) {
    val sys = createAkkaFixture()

    trace( s"sys.model = ${sys.model}" )
    trace( s"sys.context = ${sys.context}" )

    try {
      sys.before()
      test( sys )
    } finally {
      sys.after()
      sys.system.shutdown()
    }
  }

  //todo: easy support for ReliableMessage( _, Envelope( payload: TARGET_CLASS, _ ) ) matching
  //todo: focus on the target class in usage
//  def expectEventPublishedMatching[E: ClassTag]( matcher: PartialFunction[Any, Boolean] ): Unit = {
//    val probe = TestProbe()
//    system.eventStream.subscribe( probe.ref, implicitly[ClassTag[E]].runtimeClass )
//    assert( probe.expectMsgPF[Boolean](10.seconds)(matcher), s"unexpected event" )
//  }

//  def expectEventPublished[E: ClassTag](): Unit = {
//    val probe = TestProbe()
//    val clazz = implicitly[ClassTag[E]].runtimeClass
//    system.eventStream.subscribe( probe.ref, clazz )
//    probe.expectMsgClass( 10.seconds, clazz )
//  }

//  def expectFailure[E: ClassTag]( awaitable: Future[Any] ): Unit = {
//    implicit val timeout = Timeout( 5, SECONDS )
//    val future = Await.ready( awaitable, timeout.duration ).asInstanceOf[Future[Any]]
//    val futureValue = future.value.get
//    futureValue match {
//      case Failure(ex) if ex.getClass.equals( implicitly[ClassTag[E]].runtimeClass ) => () //ok
//      case x => fail( s"Unexpected result: ${x}" )
//    }
//  }
//
//  def expectReply[O]( obj: O ): Unit = expectMsg( 20.seconds, obj )
//
//  def ensureActorTerminated( actor: ActorRef ): Unit = {
//    watch( actor )
//    actor ! PoisonPill
//    // wait until reservation office is terminated
//    fishForMessage( 1.seconds ) {
//      case Terminated(_) => {
//        unwatch( actor )
//        true
//      }
//
//      case _ => false
//    }
//  }

//  def expectEventPersisted[E: ClassTag]( aggregateId: String )( when: => Unit ): Unit = {
//    expectLogMessageFromAR(
//      s"Event persisted: ${implicitly[ClassTag[E]].runtimeClass.safeSimpleName}",
//      when
//    )(
//      aggregateId
//    )
//  }
//
//  def expectEventPersisted[E]( event: E )( aggregateRootId: String )( when: => Unit ): Unit = {
//    expectLogMessageFromAR( s"Event persisted: ${event.toString}", when )( aggregateRootId )
//  }
//
//  def expectLogMessageFromAR( messageStart: String, when: => Unit )( aggregateId: String ): Unit = {
//    EventFilter.info(
//      source = s"akka://Tests/user/$domain/$aggregateId",
//      start = messageStart, occurrences = 1)
//      .intercept {
//      when
//    }
//  }
//
//  def expectExceptionLogged[E <: Throwable](when: => Unit)(implicit t: ClassTag[E]) {
//    EventFilter[E](occurrences = 1) intercept {
//      when
//    }
//  }
//
//  def expectLogMessageFromOffice(messageStart: String)(when: => Unit) {
//    EventFilter.info(
//      source = s"akka://Tests/user/$domain",
//      start = messageStart, occurrences = 1)
//      .intercept {
//      when
//    }
//  }
//
}
