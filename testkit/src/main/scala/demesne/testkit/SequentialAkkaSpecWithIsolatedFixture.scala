package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import demesne.DomainModel
import org.scalatest.{MustMatchers, Outcome, fixture}
import peds.commons.log.Trace

import scala.concurrent.Await
import scala.concurrent.duration._


object SequentialAkkaSpecWithIsolatedFixture {
  val sysId = new AtomicInteger()
}

// Runs each test sequentially but provides fixture isolation
trait SequentialAkkaSpecWithIsolatedFixture extends fixture.WordSpec with MustMatchers {
  import demesne.testkit.SequentialAkkaSpecWithIsolatedFixture._
  private val trace = Trace[SequentialAkkaSpecWithIsolatedFixture]

  type Fixture <: TestKit
  type FixtureParam = Fixture

  class AkkaFixture( id: Int = sysId.incrementAndGet(), config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( name = s"Isolated-${id}", config ) )
  with ImplicitSender {
    //todo need to figure out how to prevent x-test clobbering of DM across suites
    implicit val model: DomainModel = {
      val result = DomainModel.register( s"DomainModel-Isolated-${id}" )( system ) map { Await.result( _, 1.second ) }
      result.toOption.get
    }
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
