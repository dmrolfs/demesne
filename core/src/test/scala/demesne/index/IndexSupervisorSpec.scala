package demesne.index

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import akka.actor._
import akka.event.LoggingReceive
import akka.testkit._
import com.typesafe.config.Config
import cats.syntax.either._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.Tag
import omnibus.commons.ErrorOr
import omnibus.commons.identifier.{ Identifying, ShortUUID }
import omnibus.commons.log.Trace
import demesne._
import demesne.index.IndexSupervisor._
import demesne.index.local.IndexLocalAgent
import demesne.repository.CommonLocalRepository
import demesne.testkit.ParallelAkkaSpec

object IndexSupervisorSpec {
  val sysId = new AtomicInteger()
}

/**
  * Created by damonrolfs on 9/18/14.
  */
class IndexSupervisorSpec extends ParallelAkkaSpec with MockitoSugar {

  private val trace = Trace[IndexSupervisorSpec]

  case class FooAdded( value: String )

  override def createAkkaFixture(
    test: OneArgTest,
    config: Config,
    system: ActorSystem,
    slug: String
  ): Fixture = {
    new Fixture( config, system, slug )
  }

  class Fixture( _config: Config, _system: ActorSystem, _slug: String )
      extends AkkaFixture( _config, _system, _slug ) {
    private val trace = Trace[Fixture]

    override def rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType]

    def rootType( specs: IndexSpecification* ): AggregateRootType = {
      new AggregateRootType {
        override def name: String = "foo"
        override def indexes: Seq[IndexSpecification] = specs

        override type S = ShortUUID
        override val identifying: Identifying[ShortUUID] = new Identifying[ShortUUID] {
          override type ID = ShortUUID
          override val idTag: Symbol = 'foo
          override def tidOf( o: ShortUUID ): TID = tag( o )
          override def idFromString( idRep: String ): ShortUUID = ShortUUID.fromString( idRep )
          override def nextTID: ErrorOr[TID] = tag( ShortUUID() ).asRight
        }

        override def repositoryProps( implicit model: DomainModel ): Props = {
          CommonLocalRepository.props( model, this, noAggregateProps )
        }

        val noAggregateProps = ( m: DomainModel, rt: AggregateRootType ) => {
          throw new Exception( "rootType.aggregateRootProps should not be invoked" )
        }

        override def toString: String = "FooAggregateRootType"
      }
    }

    val fooIdExtractor: KeyIdExtractor = {
      case FooAdded( value ) => Directive.Record( value, value.hashCode, value.hashCode )
    }

    def testSpec(
      specName: Symbol,
      specRelaySubscription: RelaySubscription = IndexBusSubscription
    )(
      specAgentProps: AggregateRootType => Props = (rt: AggregateRootType) =>
        IndexLocalAgent.props( makeTopic[String, Int]( specName.name, rt ) ),
      specAggregateProps: AggregateRootType => Props = (rt: AggregateRootType) =>
        IndexAggregate.props( makeTopic[String, Int]( specName.name, rt ) ),
      specRelayProps: ActorPath => Props = (ap: ActorPath) => IndexRelay.props( ap, fooIdExtractor )
    ): IndexSpecification = new CommonIndexSpecification[String, Int, Int] {
      override val name: Symbol = specName
      override val keyIdExtractor: KeyIdExtractor = fooIdExtractor
      override val relaySubscription: RelaySubscription = specRelaySubscription
      override def agentProps( rootType: AggregateRootType ): Props = specAgentProps( rootType )
      override def aggregateProps( rootType: AggregateRootType ): Props =
        specAggregateProps( rootType )
      override def relayProps( aggregatePath: ActorPath ): Props = specRelayProps( aggregatePath )
      override def toString: String =
        s"TestIndexSpecification(${name.name}, ${classOf[String]}:${classOf[Int]})"
    }

    val constituents = Seq( Agent, Aggregate, Relay )
    val registrant = TestProbe()

    val bus = mock[IndexBus]

    val busSpec = testSpec( 'busFoo )()
    val busRoot = rootType( busSpec )

    val contextSpec = testSpec( 'contextFoo, ContextChannelSubscription( classOf[FooAdded] ) )()
    val contextRoot = rootType( contextSpec )
  }

  object WIP extends Tag( "wip" )

  def childNameFor(
    prefix: String,
    rootType: AggregateRootType,
    spec: IndexSpecification
  ): String = {
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

  def nameFor(
    constituent: IndexConstituent,
    rootType: AggregateRootType,
    spec: IndexSpecification
  ): String = {
    constituent.category.name + "-" + spec.topic( rootType )
  }

  trait TestConstituencyProviders extends ConstituencyProvider { outer: Actor =>
    def paths: Map[IndexConstituent, ActorPath]

    override def pathFor(
      registrantType: AggregateRootType,
      spec: IndexSpecification
    )(
      constituent: IndexConstituent
    ): ActorPath =
      paths.get( constituent ) getOrElse super.pathFor( registrantType, spec )( constituent )

    override def constituencyFor(
      registrantType: AggregateRootType,
      spec: IndexSpecification
    ): List[RegisterConstituentRef] = {
      val p = pathFor( registrantType, spec ) _
      List(
        RegisterConstituentRef( Agent, p( Agent ), spec agentProps registrantType ),
        RegisterConstituentRef( Aggregate, p( Aggregate ), spec aggregateProps registrantType ),
        RegisterConstituentRef( Relay, p( Relay ), spec relayProps p( Aggregate ) )
      )
    }
  }

  def registerSupervisorProps(
    bus: IndexBus,
    constituentPaths: Map[IndexConstituent, ActorPath]
  ): Props = {
    Props(
      new IndexSupervisor( bus ) with TestConstituencyProviders {
        override val paths: Map[IndexConstituent, ActorPath] = constituentPaths
      }
    )
  }

  class TestConstituent extends Actor with ActorLogging {
    override def receive: Actor.Receive = LoggingReceive {
      case WaitingForStart => sender() ! Started

      case "stop" => {
        log debug s"KILLING TestConstituent: ${self.path}"
        throw new Exception( s"KILLING constituent: ${self.path}" )
      }
    }
  }

  "IndexSupervisor should" should {

    "index for spec with bus subscription" taggedAs (WIP) in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[IndexSupervisor]( IndexSupervisor.props( f.bus ) )
      real.receive( IndexSupervisor.RegisterIndex( f.busRoot, f.busSpec ), f.registrant.ref )

      f.registrant.expectMsgPF(
        3.seconds.dilated,
        s"registered[type=${f.busRoot}, spec=${f.busSpec}]"
      ) {
        case IndexRegistered( _, f.busRoot, f.busSpec ) => true
      }

      val expected = f.constituents.map { nameFor( _, f.busRoot, f.busSpec ) }.toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be( expected )
    }

    "index index for spec with context subscription" in { implicit f: Fixture =>
      implicit val system = f.system
      val real = TestActorRef[IndexSupervisor]( IndexSupervisor.props( f.bus ) )
      real.receive(
        IndexSupervisor.RegisterIndex( f.contextRoot, f.contextSpec ),
        f.registrant.ref
      )

      f.registrant.expectMsgPF(
        3.seconds.dilated,
        s"registered[${f.contextRoot}, ${f.contextSpec}]"
      ) {
        case IndexRegistered( _, f.contextRoot, f.contextSpec ) => true
      }

      val expected = f.constituents.map { nameFor( _, f.contextRoot, f.contextSpec ) }.toSet
      val actual: Set[String] = real.children.map( _.path.name ).toSet

      actual must be( expected )
    }

    "supervisor restarts index constituent upon failure" in { implicit f: Fixture =>
      implicit val system = f.system
      val aggregateProbe = TestProbe()
      val relayProbe = TestProbe()
      val constituentPaths: Map[IndexConstituent, ActorPath] = Map(
        Aggregate -> aggregateProbe.ref.path,
        Relay     -> relayProbe.ref.path
      )
      val restartSpec = f.testSpec( 'testFoo )(
        specAgentProps = (rt: AggregateRootType) => Props( new TestConstituent )
      )
      val restartRoot = f.rootType( restartSpec )
      val real = TestActorRef( registerSupervisorProps( f.bus, constituentPaths ) )
      real.receive( IndexSupervisor.RegisterIndex( restartRoot, restartSpec ), f.registrant.ref )

      aggregateProbe expectMsg index.WaitingForStart
      aggregateProbe reply index.Started

      relayProbe expectMsg index.WaitingForStart
      relayProbe reply index.Started

      trace( s"###### LOOKING FOR = IndexRegistered( _, $restartRoot, $restartSpec )  ######" )
      f.registrant.expectMsgPF() { case IndexRegistered( _, restartRoot, restartSpec ) => true }
      val agent = real.getSingleChild( nameFor( Agent, restartRoot, restartSpec ) )
      val monitor = TestProbe()
      monitor watch agent
      agent ! "stop"
      monitor.expectNoMsg( 3.seconds.dilated )
    }

//todo: uncomment and add tests

//    "no startups after initial create in node" in { implicit f: Fixture =>
//      implicit val system = f.system
//      val spec = IndexLocalAgent.spec[String, Int]( 'foo ){
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
//      val real = indexRegistrationFor( rt, spec, constituency )
//      expectStartWorkflow( rt, spec, Seq() )
//    }
//
//    "relay startup after initial create in node" in { implicit f: Fixture =>
//      implicit val system = f.system
//      val spec = IndexLocalAgent.spec[String, Int]( 'foo ){
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
//      val real = indexRegistrationFor( rt, spec, constituency )
//      expectStartWorkflow( rt, spec, Seq( Relay ) )
//    }
  }
}
