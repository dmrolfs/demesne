package demesne.index

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

import akka.actor._
import akka.testkit._
import demesne._
import demesne.index.IndexSupervisor._
import demesne.index.local.IndexLocalAgent
import demesne.testkit.ParallelAkkaSpec
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Outcome, Tag}
import peds.akka.supervision.IsolatedLifeCycleSupervisor.{ChildStarted, StartChild}
import peds.commons.log.Trace


object AggregateIndexRegistrationSpec {
  val sysId = new AtomicInteger()
}

/**
 * Created by damonrolfs on 9/18/14.
 */
class AggregateIndexRegistrationSpec extends ParallelAkkaSpec with MockitoSugar {

  private val trace = Trace[AggregateIndexRegistrationSpec]

//  override type Fixture = AuthorListingFixture
  case class FooAdded( value: String )

  type ConstituentProbes = Map[IndexConstituent, TestProbe]


  class Fixture extends AkkaFixture {
    private val trace = Trace[Fixture]

    def before( test: OneArgTest ): Unit = trace.block( "before" ) { }
    def after( test: OneArgTest ): Unit = trace.block( "after" ) { }

    val supervisor = TestProbe()
    val registrant = TestProbe()
    val constituent = TestProbe()
    val bus = mock[IndexBus]

    def rootType( specs: IndexSpecification* ): AggregateRootType = {
      new AggregateRootType {
        override def name: String = "foo"
        override def indexes: Seq[IndexSpecification] = specs
        override def aggregateRootProps(implicit model: DomainModel): Props = {
          throw new Exception( "rootType.aggregateRootProps should not be invoked" )
        }
      }
    }
  }

  override def withFixture( test: OneArgTest ): Outcome = trace.block( s"withFixture(${test}})" ) {
    val fixture = createAkkaFixture( test )

    try {
      fixture before test
      test( fixture )
    } finally {
      fixture after test
      fixture.system.terminate()
    }
  }

  override def createAkkaFixture( test: OneArgTest ): Fixture = new Fixture

  object WIP extends Tag( "wip" )

  def childNameFor( prefix: String, rootType: AggregateRootType, spec: IndexSpecification ): String = {
    s"${prefix}_${rootType.name}-${spec topic rootType}"
  }

  def constituencyFor(
    probes: Map[IndexConstituent, ActorRef],
    registrantType: AggregateRootType,
    spec: IndexSpecification
  ): List[RegisterConstituentRef] = {
    val aggregatePath = probes( Aggregate ).path
    List(
      RegisterConstituentRef( Agent, probes( Agent ).path, spec agentProps registrantType ),
      RegisterConstituentRef( Aggregate, aggregatePath, spec aggregateProps registrantType ),
      RegisterConstituentRef( Relay, probes( Relay ).path, spec relayProps aggregatePath )
    )
  }

  def indexRegistrationFor(
    rootType: AggregateRootType,
    spec: IndexSpecification,
    constituency: List[RegisterConstituentRef]
  )(
    implicit system: ActorSystem, f: Fixture
  ): TestActorRef[IndexRegistration] = TestActorRef[IndexRegistration](
    IndexRegistration.props(
      supervisor = f.supervisor.ref,
      constituency = constituency,
      subscription = Right( (f.bus, spec.relayClassifier(rootType)) ),
      spec = spec,
      registrant = f.registrant.ref,
      registrantType = rootType
    )
  )

  def expectStartWorkflow(
    rootType: AggregateRootType,
    spec: IndexSpecification,
    constituentProbes: Map[IndexConstituent, TestProbe],
    toCheck: Set[IndexConstituent]
  )(
    implicit system: ActorSystem, f: Fixture
  ): Unit = trace.block( "expectStartWorkflow" ) {
    trace( s"rootType = $rootType" )
    trace( s"spec = $spec" )
    trace( s"constituentProbes = $constituentProbes" )
    trace( s"constituentRefs = ${constituentProbes.map( cp => (cp._1 -> cp._2.ref) )}" )
    trace( s"toCheck = $toCheck" )

    toCheck foreach { c =>
      f.supervisor.expectMsgPF( hint="Start "+c.category.name ) {
        case StartChild( _, name ) => true
      }
      f.supervisor reply ChildStarted( f.constituent.ref )
    }

    val eff = constituentProbes ++ toCheck.map( _ -> f.constituent )
    eff foreach { cp =>
      val p = cp._2
      p.expectMsg( index.WaitingForStart )
      p.reply( index.Started )
    }

    f.registrant.expectMsgPF(
      3.seconds.dilated,
      s"registered[${constituentProbes.size}]"
    ) {
      case IndexRegistered(_, rootType, spec) => true
    }
  }

  "IndexRegistration should" should {

    "survey upon first create" in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ){
        case FooAdded( name ) => Directive.Record(name, name.hashCode, name.hashCode)
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ){ TestProbe() } ):_* )

      val constituency = constituencyFor(
        probes map { kp => (kp._1 -> kp._2.ref) },
        rt,
        spec
      )
      probes.values foreach { _.ref ! PoisonPill }

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, probes.keySet )
    }

    "no startups after initial create in node" in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ){
        case FooAdded( name ) => Directive.Record(name, name.hashCode, name.hashCode)
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ){ TestProbe() } ):_* )
      val constituency = constituencyFor(
        probes map { kp => (kp._1 -> kp._2.ref) },
        rt,
        spec
      )

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, Set() )
    }

    "relay startup after initial create in node" taggedAs(WIP) in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ){
        case FooAdded( name ) => Directive.Record(name, name.hashCode, name.hashCode)
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ){ TestProbe() } ):_* )
      val constituency = constituencyFor(
        probes map { kp => (kp._1 -> kp._2.ref) },
        rt,
        spec
      )

      probes.values.head.ref ! PoisonPill

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, Set( Relay ) )
    }
  }
}
