package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import demesne.DomainModel
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace


object ParallelAkkaTest {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
trait ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution {
  import demesne.testkit.ParallelAkkaTest._
  private val trace = Trace[ParallelAkkaSpec]

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  class AkkaFixture( id: Int = sysId.incrementAndGet(), config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( s"Parallel-${id}", config ) )
  with ImplicitSender {
    //todo need to figure out how to prevent x-test clobbering of DM across suites
    implicit val model: DomainModel = DomainModel.register( s"DomainModel-Parallel-${id}" )( system )
  }

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
