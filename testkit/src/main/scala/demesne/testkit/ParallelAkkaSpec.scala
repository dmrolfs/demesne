package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}


object ParallelAkkaTest {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
trait ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution {
  import demesne.testkit.ParallelAkkaTest._

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  class AkkaFixture extends TestKit( ActorSystem( s"Parallel-${sysId.incrementAndGet()}" ) ) with ImplicitSender

  def createAkkaFixture(): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val sys = createAkkaFixture()
    try {
      test( sys )
    } finally {
      sys.system.shutdown()
    }
  }
}
