package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.duration._
import scalaz.{-\/, \/, \/-}
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace
import peds.commons.util._
import demesne.{AggregateRootType, BoundedContext, DomainModel, StartTask}


object ParallelAkkaSpec {
  val testPosition: AtomicInteger = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
abstract class ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution with StrictLogging {
  private val trace = Trace[ParallelAkkaSpec]

  def testPosition: AtomicInteger = ParallelAkkaSpec.testPosition

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  abstract class AkkaFixture( val config: Config, _system: ActorSystem, val slug: String )
  extends TestKit( _system ) with ImplicitSender {
    def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      import scala.concurrent.ExecutionContext.global
      val timeout = akka.util.Timeout( 5.seconds )
      Await.ready( boundedContext.start()( global, timeout ), timeout.duration )
    }

    def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    def rootTypes: Set[AggregateRootType]
    lazy val boundedContext: BoundedContext = {
      Await.result( BoundedContext.make( Symbol(slug), config, rootTypes ), 5.seconds )
    }

    implicit lazy val model: DomainModel = boundedContext.unsafeModel
  }


  def testSlug( test: OneArgTest ): String = s"Par-${getClass.safeSimpleName}-${ParallelAkkaSpec.testPosition.incrementAndGet()}"
  def testConfiguration( test: OneArgTest, slug: String ): Config = demesne.testkit.config
  def testSystem( test: OneArgTest, config: Config, slug: String ): ActorSystem = ActorSystem( name = slug, config )
  def createAkkaFixture( test: OneArgTest, config: Config, system: ActorSystem, slug: String ): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = \/ fromTryCatchNonFatal {
      val slug = testSlug( test )
      val config = testConfiguration( test, slug )
      val system = testSystem( test, config, slug )
      createAkkaFixture( test, config, system, slug )
    }

    val results = fixture map { f =>
      logger.debug( ".......... before test .........." )
      f before test
      logger.debug( "++++++++++ starting test ++++++++++" )
      ( test(f), f )
    }

    val outcome = results map { case (outcome, f) =>
      logger.debug( "---------- finished test ------------" )
      f after test
      logger.debug( ".......... after test .........." )

      Option(f.system) foreach { s =>
        val terminated = s.terminate()
        Await.ready( terminated, 1.second )
      }

      outcome
    }

    outcome match {
      case \/-( o ) => o
      case -\/( ex ) => {
        logger.error( s"test[${test.name}] failed", ex )
        throw ex
      }
    }
  }

}
