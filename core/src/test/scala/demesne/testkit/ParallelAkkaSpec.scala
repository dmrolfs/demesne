package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace
import demesne.{AggregateRootType, BoundedContext, DomainModel}


object ParallelAkkaSpec {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
abstract class ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution {
  import demesne.testkit.ParallelAkkaSpec._
  private val trace = Trace[ParallelAkkaSpec]

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  abstract class AkkaFixture( val fixtureId: Int = sysId.incrementAndGet(), val config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( s"Parallel-${fixtureId}", config ) )
  with ImplicitSender {
    def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      Await.ready( boundedContext.start()( scala.concurrent.ExecutionContext.global ), 5.seconds )
    }

    def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    def rootTypes: Set[AggregateRootType]
    lazy val boundedContext: BoundedContext = {
      Await.result( BoundedContext.make( Symbol(s"Parallel-${fixtureId}"), config, rootTypes ), 5.seconds )
    }

    implicit lazy val model: DomainModel = boundedContext.unsafeModel
  }

  def createAkkaFixture( test: OneArgTest ): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = createAkkaFixture( test )
    try {
      fixture before test
      test( fixture )
    } finally {
      fixture after test
      fixture.system.terminate()
    }
  }
}
