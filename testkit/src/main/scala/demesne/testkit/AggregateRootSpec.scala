package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.{ ActorRef, ActorSystem }
import akka.testkit._

import com.typesafe.config.Config
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import omnibus.commons.identifier.{ Identifying, TaggedID }
import omnibus.commons.log.Trace
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
    with BeforeAndAfterAll {
  private val trace = Trace[AggregateRootSpec[A]]

  type State
  type ID
  type TID = TaggedID[ID]

  import scala.language.higherKinds
  type Protocol <: AggregateProtocol[ID]
  val protocol: Protocol

  abstract class AggregateFixture( _config: Config, _system: ActorSystem, _slug: String )
      extends AkkaFixture( _config, _system, _slug ) { fixture =>
    private val trace = Trace[AggregateFixture]

    val module: AggregateRootModule[State, ID]

    import akka.util.Timeout
    implicit val actorTimeout = Timeout( 5.seconds )

    override def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      import akka.pattern.AskableActorSelection
      val supervisorSel = new AskableActorSelection(
        system actorSelection s"/user/${boundedContext.name}-repositories"
      )

      Await.ready( (supervisorSel ? StartProtocol.WaitForStart), 5.seconds )
      logger.debug(
        "model from started BoundedContext = [{}] with root-types=[{}]",
        boundedContext.unsafeModel,
        boundedContext.unsafeModel.rootTypes.mkString( ", " )
      )
    }

    val bus = TestProbe()
    system.eventStream.subscribe( bus.ref, classOf[protocol.Event] )

    def rootTypes: Set[AggregateRootType]
    def resources: Map[Symbol, Any] = Map( Symbol( "dummy-user-resource" ) -> 3.14159 )

    def startTasks( system: ActorSystem ): Set[StartTask] = {
      Set(
        StartTask.withFunction( "start-task-1" ) { bc =>
          logger.info( "test-start-task1: bounded context:[{}]", bc.name )
          Map( Symbol( "from-start-task-1" ) -> "resource sourced from start task 1" )
        },
        StartTask.withFunction( "start-task-2" ) { bc =>
          logger.info( "test-start-task2: bounded context:[{}]", bc.name )
          Map( Symbol( "from-start-task-2" ) -> "resource sourced from start task 2" )
        },
        StartTask.withFunction( "unit-start-task-3" ) { bc =>
          akka.Done
        }
      )
    }

    def nextId(): TID
    lazy val tid: TID = nextId()

    lazy val entityRef: ActorRef = module aggregateOf tid.asInstanceOf[module.TID]

    lazy val boundedContext: BoundedContext = trace.block( s"FIXTURE: boundedContext($slug)" ) {
      val key = Symbol( s"BoundedContext-${slug}" )

      val bc = for {
        made <- BoundedContext.make(
          key,
          config,
          userResources = resources,
          startTasks = startTasks( system )
        )
        filled <- made addAggregateTypes rootTypes
        _ <- filled.futureModel map { m =>
          logger.debug( "TEST: future model new rootTypes:[{}]", m.rootTypes.mkString( ", " ) ); m
        }
        started <- filled.start()
      } yield started

      val result = Await.result( bc, 5.seconds )
      logger.debug(
        "Bounded Context root-type:[{}]",
        result.unsafeModel.rootTypes.mkString( ", " )
      )
      result
    }

    implicit lazy val model: DomainModel = trace.block( "model" ) {
      Await.result( boundedContext.futureModel, 6.seconds )
    }
  }

  override type Fixture <: AggregateFixture

  object WIP extends Tag( "wip" )
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
