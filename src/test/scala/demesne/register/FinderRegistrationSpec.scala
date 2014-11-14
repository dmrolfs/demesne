package demesne.register

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.Props
import akka.testkit.{TestActorRef, TestProbe}
import demesne._
import demesne.register.RegisterSupervisor.FinderRegistered
import demesne.register.RegisterSupervisor.FinderRegistration.{Agent, Aggregate, Relay, RegisterConstituent}
import demesne.register.local.RegisterLocalAgent
import demesne.testkit.ParallelAkkaSpec
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Outcome, Tag}
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.commons.log.Trace

import scala.concurrent.duration._


object FinderRegistrationSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/18/14.
 */
class FinderRegistrationSpec extends ParallelAkkaSpec with MockitoSugar {

  private val trace = Trace[FinderRegistrationSpec]

//  override type Fixture = AuthorListingFixture
  case class FooAdded( value: String )

  class Fixture extends AkkaFixture {
    private val trace = Trace[Fixture]

    def before(): Unit = trace.block( "before" ) { }
    def after(): Unit = trace.block( "after" ) { }

    val supervisor = TestProbe()
    val registrant = TestProbe()
    val bus = mock[RegisterBus]
    def rootType( specs: FinderSpec[_,_]* ): AggregateRootType = new AggregateRootType {
      override def name: String = "foo"
      override def finders: Seq[FinderSpec[_, _]] = specs
      override def aggregateRootProps(implicit model: DomainModel): Props = {
        throw new Exception( "rootType.aggregateRootProps should not be invoked" )
      }
    }
  }

  override def withFixture( test: OneArgTest ): Outcome = trace.block( s"withFixture(${test}})" ) {
    val sys = createAkkaFixture()

    try {
      sys.before()
      test( sys )
    } finally {
      sys.after()
      sys.system.shutdown()
    }
  }

  override def createAkkaFixture(): Fixture = new Fixture

  object WIP extends Tag( "wip" )

  def childNameFor( prefix: String, rootType: AggregateRootType, spec: FinderSpec[_,_] ): String = {
    s"${prefix}_${rootType.name}-${spec topic rootType}"
  }

  "FinderRegistration should" should {
    import demesne.register.RegisterSupervisor.FinderRegistration

    "survey upon create" in { f: Fixture =>
      implicit val system = f.system
      val spec = RegisterLocalAgent.spec[String, Int]( 'foo ){
        case FooAdded( name ) => (name, name.hashCode)
      }
      val rt = f.rootType( spec )
      val real = TestActorRef[FinderRegistration](
        FinderRegistration.props(
          supervisor = f.supervisor.ref,
          subscription = Right( (f.bus, spec.relayClassifier(rt)) ),
          spec = spec,
          registrant = f.registrant.ref,
          registrantType = rt
        )
      )

      def expectStartWorkflow(
        rootType: AggregateRootType,
        spec: FinderSpec[_,_],
        pieces: Seq[RegisterConstituent]
      ): Unit = {
        val child = TestProbe()
        pieces foreach { p =>
          f.supervisor.expectMsgPF( hint="Start "+p.category.name ) {
            case StartChild( _, name ) => name mustBe childNameFor( p.category.name, rootType, spec )
          }
          f.supervisor reply ChildStarted( child.ref )
        }

        f.registrant.expectMsg[FinderRegistered]( FinderRegistered( rootType = rt, spec = spec ) )
      }

      expectStartWorkflow( rt, spec, Seq( Relay, Aggregate, Agent ) )
    }

//    "handle PostPublished event" in { fixture: Fixture =>
//      import fixture._
//
//      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
//      val real = TestActorRef[AuthorListingModule.AuthorListing].underlyingActor
//      real.posts mustBe Vector.empty
//      real.receive( pp )
//      real.posts mustBe IndexedSeq( pp )
//    }
//
//    "respond to GetPosts requests" in { fixture: Fixture =>
//      import akka.pattern.ask
//      import fixture._
//      implicit val timeout = Timeout( 5.seconds )
//
//      val pp = PostPublished( sourceId = nextPostId, author = "Damon", title = "Handle Publishing" )
//      val ref = TestActorRef[AuthorListingModule.AuthorListing]
//      val real = ref.underlyingActor
//      val expected: immutable.IndexedSeq[PostPublished] = immutable.IndexedSeq( pp )
//      real.posts mustBe Vector.empty
//      val r1 = ref ? GetPosts("Damon")
//      val Success(Posts(a1)) = r1.value.get
//      a1 mustBe immutable.IndexedSeq.empty
//
//      real.receive( pp )
//      val r2 = ref ? GetPosts( "Damon" )
//      val Success(Posts(a2)) = r2.value.get
//      a2 mustBe immutable.IndexedSeq( pp )
//    }
  }
}
