package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorRef
import akka.testkit._
import scalaz._
import Scalaz._
import com.typesafe.config.Config
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import peds.commons.identifier.TaggedID
import peds.commons.log.Trace
import demesne._
import demesne.repository.StartProtocol


object AggregateRootSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/17/14.
 */
abstract class AggregateRootSpec[A: ClassTag]
extends SequentialAkkaSpecWithIsolatedFixture
with MockitoSugar
with BeforeAndAfterAll
{
  private val trace = Trace[AggregateRootSpec[A]]

  type ID
  type TID = TaggedID[ID]

  import scala.language.higherKinds
  type Protocol <: AggregateProtocol[ID]
  val protocol: Protocol

  abstract class AggregateFixture(
    override val fixtureId: Int = AggregateRootSpec.sysId.incrementAndGet(),
    override val config: Config = demesne.testkit.config
  ) extends AkkaFixture( fixtureId, config ) { fixture =>
    logger.debug( "FIXTURE ID = [{}]", id.toString )
    private val trace = Trace[AggregateFixture]

    val module: AggregateRootModule

    import akka.util.Timeout
    implicit val timeout = Timeout( 5.seconds )

    def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      import akka.pattern.AskableActorSelection
      val supervisorSel = new AskableActorSelection( system actorSelection s"/user/${boundedContext.name}-repositories" )
      implicit val timeout = Timeout( 5.seconds )

      val model = {
        for {
          m <- boundedContext.start()
          _ <- ( supervisorSel ? StartProtocol.WaitForStart )
        } yield m
      }
      val m = Await.result( model, 5.seconds )
      logger.debug( "model from started BoundedContext = [{}] with root-types=[{}]", m, m.rootTypes.mkString(", ") )
    }

    def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    val bus = TestProbe()
    system.eventStream.subscribe( bus.ref, classOf[protocol.Event] )

    def rootTypes: Set[AggregateRootType]

    def nextId(): TID
    lazy val tid: TID = nextId()

    lazy val entityRef: ActorRef = module aggregateOf tid.asInstanceOf[module.TID]

    lazy val boundedContext: BoundedContext = trace.block("boundedContext") {
      val result: BoundedContext = rootTypes.foldLeft( BoundedContext(s"BoundedContext-${fixtureId}", config) ){ (acc, rt) =>
        logger.debug( "adding [{}] to bounded context:[{}]", rt.name, (acc.name, acc.system.name) )
        acc :+ rt
      }
      val m = Await.result( result.futureModel, 5.seconds )
      logger.debug( "Bounded Context root-type:[{}]", m.rootTypes.mkString(", ") )
      result
    }

    implicit lazy val model: DomainModel = trace.block("model") { boundedContext.model }
  }

  override type Fixture <: AggregateFixture

  override def withFixture( test: OneArgTest ): Outcome = trace.block( "withFixture" ) {
    val fixture = createAkkaFixture( test )

    try {
      fixture before test
      logger.debug( "++++++++++ entering test ++++++++++" )
      test( fixture )
    } finally {
      logger.debug( "---------- exited test ------------" )
      fixture after test
      fixture.system.terminate()
    }
  }




  object WIP extends Tag( "wip" )


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
