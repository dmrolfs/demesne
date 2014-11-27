package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import demesne.DomainModel
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace

import scala.concurrent.Await
import scala.concurrent.duration._


object ParallelAkkaSpec {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
trait ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution {
  import demesne.testkit.ParallelAkkaSpec._
  private val trace = Trace[ParallelAkkaSpec]

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  class AkkaFixture( id: Int = sysId.incrementAndGet(), config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( s"Parallel-${id}", config ) )
  with ImplicitSender {
    implicit val model: DomainModel = Await.result( DomainModel.register( s"DomainModel-Parallel-${id}" )( system ), 1.second )
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
