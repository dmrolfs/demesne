package demesne.index

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._
import akka.actor._
import akka.testkit._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.Tag
import omnibus.akka.supervision.IsolatedLifeCycleSupervisor.{ ChildStarted, StartChild }
import omnibus.identifier.Identifying
import demesne._
import demesne.index.IndexSupervisor._
import demesne.index.local.IndexLocalAgent
import demesne.repository.CommonLocalRepository
import demesne.testkit.ParallelAkkaSpec
import omnibus.identifier

object AggregateIndexRegistrationSpec {
  val sysId = new AtomicInteger()

  case class Bar( id: Bar.TID )

  object Bar {
    type TID = identifying.TID
    implicit val identifying = Identifying.byShortUuid[Bar]
  }
}

/**
  * Created by damonrolfs on 9/18/14.
  */
class AggregateIndexRegistrationSpec extends ParallelAkkaSpec with MockitoSugar {
  import AggregateIndexRegistrationSpec.Bar

  case class FooAdded( value: String )

  type ConstituentProbes = Map[IndexConstituent, TestProbe]

  override def createAkkaFixture( test: OneArgTest, system: ActorSystem, slug: String ): Fixture = {
    new Fixture( slug, system )
  }

  class Fixture( _slug: String, _system: ActorSystem ) extends AkkaFixture( _slug, _system ) {
    override val rootTypes: Set[AggregateRootType] = Set.empty[AggregateRootType]

    val supervisor = TestProbe( "supervisor" )
    val registrant = TestProbe( "registrant" )
    val constituent = TestProbe( "constituent" )
    val bus = mock[IndexBus]

    def rootType( specs: IndexSpecification* ): AggregateRootType = {
      new AggregateRootType {
        override def name: String = "foo"

        override type S = Bar
//        override val identifying: Identifying[S] = implicitly[Identifying[Bar]]

        override def indexes: Seq[IndexSpecification] = specs

        override def repositoryProps( implicit model: DomainModel ): Props = {
          CommonLocalRepository.props( model, this, noAggregateProps )
        }

        val noAggregateProps = ( m: DomainModel, rt: AggregateRootType ) => {
          throw new Exception( "rootType.aggregateRootProps should not be invoked" )
        }
      }
    }
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

  def indexRegistrationFor(
    rootType: AggregateRootType,
    spec: IndexSpecification,
    constituency: List[RegisterConstituentRef]
  )(
    implicit system: ActorSystem,
    f: Fixture
  ): TestActorRef[IndexRegistration] = TestActorRef[IndexRegistration](
    IndexRegistration.props(
      supervisor = f.supervisor.ref,
      constituency = constituency,
      subscription = Right( ( f.bus, spec.relayClassifier( rootType ) ) ),
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
    implicit system: ActorSystem,
    f: Fixture
  ): Unit = {
    scribe.trace( s"rootType = $rootType" )
    scribe.trace( s"spec = $spec" )
    scribe.trace( s"constituentProbes = $constituentProbes" )
    scribe.trace( s"constituentRefs = ${constituentProbes.map( cp => (cp._1 -> cp._2.ref) )}" )
    scribe.trace( s"toCheck = $toCheck" )

    toCheck foreach { c =>
      f.supervisor.expectMsgPF( hint = "Start " + c.category.name ) {
        case _: StartChild => true
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
      case _: IndexRegistered => true
    }
  }

  "IndexRegistration should" should {

    "survey upon first create" taggedAs WIP in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ) {
        case FooAdded( name ) => Directive.Record( name, name.hashCode, name.hashCode )
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ) {
        TestProbe()
      } ): _* )

      val constituency = constituencyFor(
        probes map { kp =>
          (kp._1 -> kp._2.ref)
        },
        rt,
        spec
      )
      probes.values foreach { _.ref ! PoisonPill }

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, probes.keySet )
    }

    "no startups after initial create in node" in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ) {
        case FooAdded( name ) => Directive.Record( name, name.hashCode, name.hashCode )
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ) {
        TestProbe()
      } ): _* )
      val constituency = constituencyFor(
        probes map { kp =>
          (kp._1 -> kp._2.ref)
        },
        rt,
        spec
      )

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, Set() )
    }

    "relay startup after initial create in node" in { implicit f: Fixture =>
      implicit val system = f.system
      val spec = IndexLocalAgent.spec[String, Int, Int]( 'foo ) {
        case FooAdded( name ) => Directive.Record( name, name.hashCode, name.hashCode )
      }
      val rt = f.rootType( spec )
      val probes: ConstituentProbes = Map( Seq( Relay, Aggregate, Agent ).zip( Seq.fill( 3 ) {
        TestProbe()
      } ): _* )
      val constituency = constituencyFor(
        probes map { kp =>
          (kp._1 -> kp._2.ref)
        },
        rt,
        spec
      )

      probes.values.head.ref ! PoisonPill

      val real = indexRegistrationFor( rt, spec, constituency )
      expectStartWorkflow( rt, spec, probes, Set( Relay ) )
    }
  }
}
