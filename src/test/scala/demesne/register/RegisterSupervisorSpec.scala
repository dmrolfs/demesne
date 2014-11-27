package demesne.register

import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.event.LoggingReceive
import akka.testkit._
import demesne._
import demesne.register.RegisterSupervisor._
import demesne.register.local.RegisterLocalAgent
import demesne.testkit.ParallelAkkaSpec
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Outcome, Tag}
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

    def before(): Unit = { }
    def after(): Unit = { }

    def rootType( specs: FinderSpec[_,_]* ): AggregateRootType = new AggregateRootType {
      override def name: String = "foo"
      override def finders: Seq[FinderSpec[_, _]] = specs
      override def aggregateRootProps(implicit model: DomainModel): Props = {
        throw new Exception( "rootType.aggregateRootProps should not be invoked" )
      }

      override def toString: String = "FooAggregateRootType"
    }

    val fooIdExtractor: KeyIdExtractor[String, Int] = {
      case FooAdded(value) => (value, value.hashCode)
    }

    def testSpec(
      specName: Symbol,
      specRelaySubscription: RelaySubscription = RegisterBusSubscription
    )(
      specAgentProps: AggregateRootType => Props = (rt: AggregateRootType) => RegisterLocalAgent.props( makeTopic( specName.name, rt, classOf[String], classOf[Int]) ),
      specAggregateProps: AggregateRootType => Props = (rt: AggregateRootType) => RegisterAggregate.props( makeTopic(specName.name, rt, classOf[String], classOf[Int]) ),
      specRelayProps: ActorPath => Props = (ap: ActorPath) => RegisterRelay.props[String, Int](ap, fooIdExtractor )
    ): FinderSpec[String, Int] = new FinderSpec[String, Int] {
      override val name: Symbol = specName
      override val keyIdExtractor: KeyIdExtractor[String, Int] = fooIdExtractor
      override val relaySubscription: RelaySubscription = specRelaySubscription
      override def agentProps(rootType: AggregateRootType): Props = specAgentProps(rootType)
      override def aggregateProps(rootType: AggregateRootType): Props = specAggregateProps(rootType)
      override def relayProps(aggregatePath: ActorPath): Props = specRelayProps(aggregatePath)
      override def toString: String = s"TestFinderSpec(${name.name}, ${classOf[String]}:${classOf[Int]})"
    }

    val constituents = Seq( Agent, Aggregate, Relay )
    val registrant = TestProbe()

    val bus = mock[RegisterBus]

    val busSpec = testSpec( 'busFoo )()
    val busRoot = rootType( busSpec )

    val contextSpec = testSpec( 'contextFoo, ContextChannelSubscription(classOf[FooAdded]) )()
    val contextRoot = rootType( contextSpec )
  }

  override def withFixture( test: OneArgTest ): Outcome = {
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

  def nameFor( constituent: RegisterConstituent, rootType: AggregateRootType, spec: FinderSpec[_,_] ): String = {
    constituent.category.name + "-" + spec.topic( rootType )
  }

  trait TestConstituencyProviders extends ConstituencyProvider { outer: Actor =>
    def paths: Map[RegisterConstituent, ActorPath]

    override def pathFor(
      registrantType: AggregateRootType,
      spec: FinderSpec[_, _]
    )(
      constituent: RegisterConstituent
    ): ActorPath = paths.get( constituent ) getOrElse super.pathFor(registrantType, spec)( constituent )

    override def constituencyFor(
      registrantType: AggregateRootType,
      spec: FinderSpec[_, _]
    ): List[RegisterConstituentRef] = {
      val p = pathFor( registrantType, spec ) _
      List(
        RegisterConstituentRef( Agent, p(Agent), spec agentProps registrantType ),
        RegisterConstituentRef( Aggregate, p(Aggregate), spec aggregateProps  registrantType ),
        RegisterConstituentRef( Relay, p(Relay), spec relayProps p(Aggregate) )
      )
    }
  }

  def registerSupervisorProps( bus: RegisterBus, constituentPaths: Map[RegisterConstituent, ActorPath] ): Props = {
    Props(
      new RegisterSupervisor( bus ) with TestConstituencyProviders {
        override val paths: Map[RegisterConstituent, ActorPath] = constituentPaths
      }
    )
  }

  class TestConstituent extends Actor with ActorLogging {
    override def receive: Actor.Receive = {
      case WaitingForStart => sender() ! Started

      case "stop" => LoggingReceive {
        log info s"KILLING TestConstituent: ${self.path}"
        throw new Exception( s"KILLING constituent: ${self.path}" )
      }
    }
  }


  "RegisterSupervisor should" should {

    "register finder for spec with bus subscription" in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[RegisterSupervisor]( RegisterSupervisor.props( f.bus ) )
      real.receive( RegisterSupervisor.RegisterFinder( f.busRoot, f.busSpec ), f.registrant.ref )

      f.registrant.expectMsgPF(
        800.millis.dilated,
        s"registered[type=${f.busRoot}, spec=${f.busSpec}]"
      ) {
        case FinderRegistered( _, f.busRoot, f.busSpec ) => true
      }

      val expected = f.constituents.map{ nameFor( _, f.busRoot, f.busSpec ) }.toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be (expected)
    }

    "register finder for spec with context subscription" taggedAs(WIP) in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[RegisterSupervisor]( RegisterSupervisor.props( f.bus ) )
      real.receive( RegisterSupervisor.RegisterFinder( f.contextRoot, f.contextSpec ), f.registrant.ref )

      f.registrant.expectMsgPF(
        800.millis.dilated,
        s"registered[${f.contextRoot}, ${f.contextSpec}]"
      ) {
        case FinderRegistered( _, f.contextRoot, f.contextSpec ) => true
      }

      val expected = f.constituents.map{ nameFor( _, f.contextRoot, f.contextSpec) }.toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be (expected)
    }

    "supervisor restarts register constituent upon failure" in { implicit f: Fixture =>
      implicit val system = f.system
      val aggregateProbe = TestProbe()
      val relayProbe = TestProbe()
      val constituentPaths: Map[RegisterConstituent, ActorPath] = Map(
        Aggregate -> aggregateProbe.ref.path,
        Relay -> relayProbe.ref.path
      )
      val restartSpec = f.testSpec( 'testFoo )( specAgentProps = (rt: AggregateRootType) => Props(new TestConstituent) )
      val restartRoot = f.rootType( restartSpec )
      val real = TestActorRef( registerSupervisorProps( f.bus, constituentPaths ) )
      real.receive( RegisterSupervisor.RegisterFinder( restartRoot, restartSpec), f.registrant.ref )

      aggregateProbe expectMsg register.WaitingForStart
      aggregateProbe reply register.Started

      relayProbe expectMsg register.WaitingForStart
      relayProbe reply register.Started

      trace( s"###### LOOKING FOR = FinderRegistered( _, $restartRoot, $restartSpec )  ######")
      f.registrant.expectMsgPF(){ case FinderRegistered( _, restartRoot, restartSpec) => true }
      val agent = real.getSingleChild( nameFor( Agent, restartRoot, restartSpec) )
      val monitor = TestProbe()
      monitor watch agent
      agent ! "stop"
      monitor.expectNoMsg( 500.millis.dilated )
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
