package demesne.testkit

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.Config
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.{MustMatchers, Outcome, ParallelTestExecution, fixture}
import peds.commons.log.Trace
import demesne.{AggregateRootType, BoundedContext, DomainModel}

import scalaz.{-\/, \/, \/-}


object ParallelAkkaSpec {
  val sysId = new AtomicInteger()
}


// Runs each individual test in parallel. Only possible with Fixture isolation
abstract class ParallelAkkaSpec extends fixture.WordSpec with MustMatchers with ParallelTestExecution with StrictLogging {
  import demesne.testkit.ParallelAkkaSpec._
  private val trace = Trace[ParallelAkkaSpec]

  type Fixture <: AkkaFixture
  type FixtureParam = Fixture

  abstract class AkkaFixture( val fixtureId: Int = sysId.incrementAndGet(), val config: Config = demesne.testkit.config )
  extends TestKit( ActorSystem( s"Parallel-${fixtureId}", config ) )
  with ImplicitSender {
    def before( test: OneArgTest ): Unit = trace.block( "before" ) {
      import scala.concurrent.ExecutionContext.global
      val timeout = akka.util.Timeout( 5.seconds )
      Await.ready( boundedContext.start()( global, timeout ), timeout.duration )
    }

    def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    def rootTypes: Set[AggregateRootType]
    lazy val boundedContext: BoundedContext = {
      Await.result( BoundedContext.make( Symbol(s"Parallel-${fixtureId}"), config, rootTypes ), 5.seconds )
    }

    implicit lazy val model: DomainModel = boundedContext.unsafeModel
  }

  def createAkkaFixture( test: OneArgTest ): Fixture

  override def withFixture( test: OneArgTest ): Outcome = {
    val fixture = \/ fromTryCatchNonFatal { createAkkaFixture( test ) }
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
