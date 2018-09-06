package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import cats.syntax.either._
import com.typesafe.config.Config
import org.scalatest.{ fixture, MustMatchers, Outcome }
import omnibus.core.syntax.clazz._

object SequentialAkkaSpecWithIsolatedFixture {
  val testPosition: AtomicInteger = new AtomicInteger()
}

// Runs each test sequentially but provides fixture isolation
abstract class SequentialAkkaSpecWithIsolatedFixture extends fixture.WordSpec with MustMatchers {

  def testPosition: AtomicInteger = SequentialAkkaSpecWithIsolatedFixture.testPosition

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  class AkkaFixture( val config: Config, _system: ActorSystem, val slug: String )
      extends TestKit( _system )
      with ImplicitSender {
    def before( test: OneArgTest ): Unit = {}
    def after( test: OneArgTest ): Unit = {}
  }

  def testSlug( test: OneArgTest ): String = {
    s"Seq-${getClass.safeSimpleName}-${SequentialAkkaSpecWithIsolatedFixture.testPosition.incrementAndGet()}"
  }

  def testConfiguration( test: OneArgTest, slug: String ): Config = demesne.testkit.config

  def testSystem( test: OneArgTest, config: Config, slug: String ): ActorSystem =
    ActorSystem( name = slug, config )

  def createAkkaFixture(
    test: OneArgTest,
    config: Config,
    system: ActorSystem,
    slug: String
  ): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val slug = testSlug( test )
    val config = testConfiguration( test, slug )
    val system = testSystem( test, config, slug )

    val fixture = Either catchNonFatal { createAkkaFixture( test, config, system, slug ) }

    val results = fixture map { f =>
      scribe.debug( s".......... before test [${test.name}] .........." )
      f before test
      scribe.debug( s"++++++++++ starting test [${test.name}] ++++++++++" )
      ( test( f ), f )
    }

    val outcome = results map {
      case ( o, f ) =>
        scribe.debug( s"---------- finished test [${test.name}] ------------" )
        f after test
        scribe.debug( s".......... after test [${test.name}] .........." )

        Option( f.system ) foreach { s =>
          val terminated = s.terminate()
          Await.ready( terminated, 30.seconds )
        }

        o
    }

    outcome match {
      case Right( o ) => {
        Await.ready( system.terminate(), 5.seconds )
        o
      }
      case Left( ex ) => {
        Await.ready( system.terminate(), 5.seconds )
        scribe.error( s"test[${test.name}] failed", ex )
        throw ex
      }
    }
  }
}
