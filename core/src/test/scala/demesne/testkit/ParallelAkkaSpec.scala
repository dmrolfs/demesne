package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import demesne.DomainModel
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace


object ParallelAkkaSpec {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
trait ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution {
  import demesne.testkit.ParallelAkkaSpec._
  private val trace = Trace[ParallelAkkaSpec]

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  class AkkaFixture( val fixtureId: Int = sysId.incrementAndGet(), val config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( s"Parallel-${fixtureId}", config ) )
  with ImplicitSender {
    implicit val model: DomainModel = {
      import scala.concurrent.ExecutionContext.Implicits.global
      val result = DomainModel.make( s"Parallel-${fixtureId}" )( system, global ) map {Await.result( _, 1.second ) }
      result.toOption.get
    }
  }

  def createAkkaFixture( test: OneArgTest ): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = createAkkaFixture( test )
    try {
      test( fixture )
    } finally {
      fixture.system.terminate()
    }
  }
}
