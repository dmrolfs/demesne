package demesne.register

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.testkit._
import demesne._
import demesne.register.RegisterSupervisor._
import demesne.register.local.RegisterLocalAgent
import demesne.testkit.ParallelAkkaSpec
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Outcome, Tag}
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.commons.log.Trace

import scala.concurrent.duration._


object RegisterSupervisorSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/18/14.
 */
class RegisterSupervisorSpec extends ParallelAkkaSpec with MockitoSugar {

  private val trace = Trace[RegisterSupervisorSpec]

  case class FooAdded( value: String )

  class Fixture extends AkkaFixture {
    private val trace = Trace[Fixture]

    def before(): Unit = trace.block( "before" ) { }
    def after(): Unit = trace.block( "after" ) { }

    def rootType( specs: FinderSpec[_,_]* ): AggregateRootType = new AggregateRootType {
      override def name: String = "foo"
      override def finders: Seq[FinderSpec[_, _]] = specs
      override def aggregateRootProps(implicit model: DomainModel): Props = {
        throw new Exception( "rootType.aggregateRootProps should not be invoked" )
      }
    }

    val constituents = Seq( Agent, Aggregate, Relay )
    val registrant = TestProbe()

    val bus = mock[RegisterBus]

    val busSpec = RegisterLocalAgent.spec[String, Int]( 'busFoo ){
      case FooAdded( name ) => (name, name.hashCode)
    }

    val contextSpec = RegisterLocalAgent.spec[String, Int]( 'contextFoo, ContextChannelSubscription( classOf[FooAdded] ) ) {
      case FooAdded( name ) => (name, name.hashCode)
    }

    val busRoot = rootType( busSpec )
    val contextRoot = rootType( contextSpec )
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

  def constituencyFor(
    probes: Map[RegisterConstituent, ActorRef],
    registrantType: AggregateRootType,
    spec: FinderSpec[_, _]
  ): List[RegisterConstituentRef] = {
    val aggregatePath = probes( Aggregate ).path
    List(
      RegisterConstituentRef( Agent, probes( Agent ).path, spec agentProps registrantType ),
      RegisterConstituentRef( Aggregate, aggregatePath, spec aggregateProps registrantType ),
      RegisterConstituentRef( Relay, probes( Relay ).path, spec relayProps aggregatePath )
    )
  }

//  def finderRegistrationFor(
//    rootType: AggregateRootType,
//    spec: FinderSpec[_,_],
//    constituency: List[RegisterConstituentRef]
//  )(
//    implicit system: ActorSystem, f: Fixture
//  ): TestActorRef[FinderRegistration] = TestActorRef[FinderRegistration](
//    FinderRegistration.props(
//      supervisor = f.supervisor.ref,
//      constituency = constituency,
//      subscription = Right( (f.bus, spec.relayClassifier(rootType)) ),
//      spec = spec,
//      registrant = f.registrant.ref,
//      registrantType = rootType
//    )
//  )

//  def expectStartWorkflow(
//    rootType: AggregateRootType,
//    spec: FinderSpec[_,_],
//    pieces: Seq[RegisterConstituent]
//  )(
//    implicit system: ActorSystem, f: Fixture
//  ): Unit = {
//    pieces foreach { p =>
//      f.supervisor.expectMsgPF( hint="Start "+p.category.name ) {
//        case StartChild( _, name ) => true
//      }
//      f.supervisor reply ChildStarted( f.constituent.ref )
//    }
//
//    f.registrant.expectMsg[FinderRegistered](
//      3.seconds.dilated,
//      s"registered[${pieces.size}]",
//      FinderRegistered( rootType = rootType, spec = spec )
//    )
//  }


  "RegisterSupervisor should" should {

    "register finder for spec with bus subscription" in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[RegisterSupervisor]( RegisterSupervisor.props( f.bus ) )
      real.receive( RegisterSupervisor.RegisterFinder( f.busRoot, f.busSpec ), f.registrant.ref )

      f.registrant.expectMsg(
        200.millis.dilated,
        s"registered[${f.busRoot}, ${f.busSpec}]",
        FinderRegistered( rootType = f.busRoot, spec = f.busSpec )
      )

      val suffix = "-" + f.busSpec.topic( f.busRoot )
      val expected = f.constituents.map( _.category.name + suffix ).toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be (expected)
    }

    "register finder for spec with context subscription" in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[RegisterSupervisor]( RegisterSupervisor.props( f.bus ) )
      real.receive( RegisterSupervisor.RegisterFinder( f.contextRoot, f.contextSpec ), f.registrant.ref )

      f.registrant.expectMsg(
        200.millis.dilated,
        s"registered[${f.contextRoot}, ${f.contextSpec}]",
        FinderRegistered( rootType = f.contextRoot, spec = f.contextSpec)
      )

      val suffix = "-" + f.contextSpec.topic( f.contextRoot )
      val expected = f.constituents.map( _.category.name + suffix ).toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be (expected)
    }

    //    "no startups after initial create in node" in { implicit f: Fixture =>
//      implicit val system = f.system
//      val spec = RegisterLocalAgent.spec[String, Int]( 'foo ){
//        case FooAdded( name ) => (name, name.hashCode)
//      }
//      val rt = f.rootType( spec )
//      val probes = Seq.fill( 3 ){ TestProbe() }
//      val constituency = constituencyFor(
//        Map( Seq( Relay, Aggregate, Agent ).zip( probes map { _.ref } ):_* ),
//        rt,
//        spec
//      )
//
//      val real = finderRegistrationFor( rt, spec, constituency )
//      expectStartWorkflow( rt, spec, Seq() )
//    }
//
//    "relay startup after initial create in node" in { implicit f: Fixture =>
//      implicit val system = f.system
//      val spec = RegisterLocalAgent.spec[String, Int]( 'foo ){
//        case FooAdded( name ) => (name, name.hashCode)
//      }
//      val rt = f.rootType( spec )
//      val probes = Seq.fill( 3 ){ TestProbe() }
//      val constituency = constituencyFor(
//        Map( Seq( Relay, Aggregate, Agent ).zip( probes map { _.ref } ):_* ),
//        rt,
//        spec
//      )
//
//      probes.head.ref ! PoisonPill
//
//      val real = finderRegistrationFor( rt, spec, constituency )
//      expectStartWorkflow( rt, spec, Seq( Relay ) )
//    }
  }
}
