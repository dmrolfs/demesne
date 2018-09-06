package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import cats.syntax.either._
import com.typesafe.config.Config
import org.scalatest.{ fixture, MustMatchers, Outcome, ParallelTestExecution }
import omnibus.core.syntax.clazz._
import demesne.{ AggregateRootType, BoundedContext, DomainModel }

object ParallelAkkaSpec {
  val testPosition: AtomicInteger = new AtomicInteger()
}

// Runs each individual test in parallel. Only possible with Fixture isolation
abstract class ParallelAkkaSpec
    extends fixture.WordSpec
    with MustMatchers
    with ParallelTestExecution {

  def testPosition: AtomicInteger = ParallelAkkaSpec.testPosition

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  abstract class AkkaFixture( val config: Config, _system: ActorSystem, val slug: String )
      extends TestKit( _system )
      with ImplicitSender {

    def before( test: OneArgTest ): Unit = {
      import scala.concurrent.ExecutionContext.global
      val timeout = akka.util.Timeout( 5.seconds )
      Await.ready( boundedContext.start()( global, timeout ), timeout.duration )
    }

    def after( test: OneArgTest ): Unit = {}

    def rootTypes: Set[AggregateRootType]
    lazy val boundedContext: BoundedContext = {
      Await.result( BoundedContext.make( Symbol( slug ), config, rootTypes ), 5.seconds )
    }

    implicit lazy val model: DomainModel = boundedContext.unsafeModel
  }

  def testSlug( test: OneArgTest ): String =
    s"Par-${getClass.safeSimpleName}-${ParallelAkkaSpec.testPosition.incrementAndGet()}"
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

        o
    }

    outcome.fold(
      ex => {
        Await.ready( system.terminate(), 5.seconds )
        scribe.error( s"test[${test.name}] failed", ex )
        throw ex
      },
      o => {
        Await.ready( system.terminate(), 5.seconds )
        o
      }
    )
  }
}
