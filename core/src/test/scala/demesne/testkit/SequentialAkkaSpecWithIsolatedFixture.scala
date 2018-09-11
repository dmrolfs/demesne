package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.dispatch.Dispatchers
import akka.testkit.TestEvent.Mute
import akka.testkit.{ DeadLettersFilter, TestKit }
import cats.syntax.either._
import com.github.ghik.silencer.silent
import com.typesafe.config.Config
import org.scalatest.{ fixture, MustMatchers, Outcome }
import omnibus.core.syntax.clazz._
import scribe.Level
import scribe.writer.FileWriter

object SequentialAkkaSpecWithIsolatedFixture {
  val testPosition: AtomicInteger = new AtomicInteger()
}

// Runs each test sequentially but provides fixture isolation
abstract class SequentialAkkaSpecWithIsolatedFixture extends fixture.WordSpec with MustMatchers {

  initializeLogging()

  protected def initializeLogging(): Unit = {
    scribe.Logger.root
    //      .clearHandlers()
    //      .clearModifiers()
      .withHandler( writer = FileWriter.default )
      .withMinimumLevel( Level.Debug )
      .replace()
  }

  @silent def slugForTest( test: OneArgTest ): String = {
    s"Seq-${getClass.safeSimpleName}-${SequentialAkkaSpecWithIsolatedFixture.testPosition.incrementAndGet()}"
  }

  @silent def systemForTest(
    test: OneArgTest,
    slug: String,
    config: Option[Config] = None
  ): ActorSystem = {
    scribe.debug( s"creating system[${slug}] for test:[${test.name}]" )

    ActorSystem(
      name = "ClusterSystem", //slug,
      config = config,
      classLoader = None,
      defaultExecutionContext = None
    )
  }

  @silent def configurationForTest( test: OneArgTest, slug: String ): Option[Config] = {
    Option( demesne.testkit.config )
  }

  def contextForTest( test: OneArgTest ): ( String, ActorSystem ) = {
    val slug = slugForTest( test )
    val config = configurationForTest( test, slug )
    val system = systemForTest( test, slug, config )
    ( slug, system )
  }

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  def createAkkaFixture( test: OneArgTest, system: ActorSystem, slug: String ): Fixture

  class AkkaFixture(
    val slug: String,
    _system: ActorSystem
  ) extends TestKit( _system ) {
    val config = system.settings.config

    @silent def before( test: OneArgTest ): Unit = {}
    @silent def after( test: OneArgTest ): Unit = {}

    def spawn( dispatcherId: String = Dispatchers.DefaultDispatcherId )( body: => Unit ): Unit = {
      Future { body }( system.dispatchers lookup dispatcherId )
    }

    def muteDeadLetters(
      messagesClasses: Class[_]*
    )( implicit sys: ActorSystem = system ): Unit = {
      if (!sys.log.isDebugEnabled) {
        def mute( clazz: Class[_] ): Unit = {
          sys.eventStream.publish(
            Mute( DeadLettersFilter( clazz )( occurrences = Int.MaxValue ) )
          )
        }

        if (messagesClasses.isEmpty) mute( classOf[AnyRef] )
        else messagesClasses foreach mute
      }
    }
  }

  override protected def withFixture( test: OneArgTest ): Outcome = {
    val ( slug, system ) = contextForTest( test )

    Either
      .catchNonFatal { createAkkaFixture( test, system, slug ) }
      .map { f =>
        scribe.debug( s".......... before test [${test.name}] .........." )
        f before test
        scribe.debug( s"++++++++++ starting test [${test.name}] ++++++++++" )
        ( test( f ), f )
      }
      .map {
        case ( outcome, f ) =>
          scribe.debug( s"---------- finished test [${test.name}] ------------" )
          f after test
          scribe.debug( s".......... after test [${test.name}] .........." )

          Option( f.system ) foreach { s ⇒
            scribe.debug( s"terminating actor-system:${s.name}..." )
            f.shutdown( actorSystem = s, verifySystemShutdown = false )
            scribe.debug( s"actor-system:${s.name}.terminated" )
          }

          outcome
      }
      .valueOr { ex =>
        scribe.error( s"test[${test.name}] failed", ex )
        system.terminate()
        throw ex
      }
  }

  def testPosition: AtomicInteger = SequentialAkkaSpecWithIsolatedFixture.testPosition
}
