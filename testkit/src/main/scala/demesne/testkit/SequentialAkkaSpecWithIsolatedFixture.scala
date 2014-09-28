package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{Outcome, MustMatchers, fixture}


object SequentialAkkaSpecWithIsolatedFixture {
  val sysId = new AtomicInteger()
}

// Runs each test sequentially but provides fixture isolation
trait SequentialAkkaSpecWithIsolatedFixture extends fixture.WordSpec with MustMatchers {
  import SequentialAkkaSpecWithIsolatedFixture._

  type Fixture <: TestKit
  type FixtureParam = Fixture

  class AkkaFixture
  extends TestKit( ActorSystem( name = s"WithIsoFix-${sysId.incrementAndGet()}", config = config ) )
  with ImplicitSender

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
