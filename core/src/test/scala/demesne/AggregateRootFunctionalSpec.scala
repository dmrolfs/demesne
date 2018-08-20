package demesne

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.reflect._
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.testkit._
import cats.syntax.either._
import org.scalatest.concurrent.ScalaFutures

import shapeless._
import com.typesafe.config.{ Config, ConfigFactory }
import demesne.repository.CommonLocalRepository
import org.scalatest.OptionValues
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import omnibus.archetype.domain.model.core.{ Entity, EntityIdentifying, EntityLensProvider }
import omnibus.akka.envelope._
import omnibus.commons._
import omnibus.commons.identifier.{ Identifying, ShortUUID, TaggedID }
import omnibus.commons.log.Trace

/**
  * Created by rolfsd on 6/29/16.
  */
class AggregateRootFunctionalSpec
    extends demesne.testkit.AggregateRootSpec[AggregateRootFunctionalSpec]
    with ScalaFutures
    with OptionValues {
  import AggregateRootFunctionalSpec._

  private val trace = Trace[AggregateRootFunctionalSpec]

  override type State = AggregateRootFunctionalSpec.State
  override type ID = AggregateRootFunctionalSpec.Foo#ID

  override type Protocol = AggregateRootFunctionalSpec.Protocol.type
  override val protocol: Protocol = AggregateRootFunctionalSpec.Protocol

  override def testConfiguration( test: OneArgTest, slug: String ): Config =
    AggregateRootFunctionalSpec.config
  override def createAkkaFixture(
    test: OneArgTest,
    config: Config,
    system: ActorSystem,
    slug: String
  ): Fixture = {
    new Fixture( config, system, slug )
  }

  class Fixture( _config: Config, _system: ActorSystem, _slug: String )
      extends AggregateFixture( _config, _system, _slug ) {
    private val trace = Trace[Fixture]
    override val module: AggregateRootModule[State, ID] = AggregateRootFunctionalSpec.FooModule

    override def rootTypes: Set[AggregateRootType] = trace.block( "rootTypes" ) {
      Set( AggregateRootFunctionalSpec.FooModule.rootType )
    }

    override def nextId(): TID = State.stateIdentifying.nextTID.unsafeGet

    def infoFrom( ar: ActorRef )( implicit ec: ExecutionContext ): Future[Option[State]] = {
      import akka.pattern.ask
      (ar ? protocol.GetState( tid )).mapTo[protocol.MyState].map { _.state }
    }
  }

  def assertStates( actual: State, expected: State ): Unit = {
    logger.debug( "asserting actual:[{}] == expected:[{}]", actual, expected )
    actual.id.tag.name mustBe expected.id.tag.name
    actual.id.id mustBe expected.id.id
    actual.id mustBe expected.id
    actual.foo.name mustBe expected.foo.name
    actual.foo.slug mustBe expected.foo.slug
    actual.foo.f mustBe expected.foo.f
    actual.foo.b mustBe expected.foo.b
    actual.foo.z mustBe expected.foo.z
    actual.foo.## mustBe expected.foo.##
    actual.count mustBe expected.count
    actual mustBe expected
  }

  "AggregateRoot" should {
    "save and reload a snapshot" taggedAs WIP in { f: Fixture =>
      import f._
      import scala.concurrent.ExecutionContext.Implicits.global

      val e1 = State( tid, Foo( tid, "foo", "foo", b = 17 ), count = 1 )
      entityRef !+ protocol.Bar( e1.id, e1.foo.b )
      bus.expectMsgClass( classOf[protocol.Barred] )
      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, e1 )
      }

      val e2 = State( tid, Foo( tid, "foo", "foo", b = 3.14159 ), count = 2 )

      EventFilter.debug( start = "aggregate snapshot successfully saved:", occurrences = 1 ) intercept {
        logger.debug( "+ INTERCEPT" )
        entityRef !+ FooModule.rootType.snapshot.value.saveSnapshotCommand( tid )
        entityRef !+ protocol.Bar( e2.id, e2.foo.b )
        logger.debug( "TEST:SLEEPING..." )
        Thread.sleep( 3000 )
        logger.debug( "TEST:AWAKE..." )
        logger.debug( "- INTERCEPT" )
      }

      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, e2 )
      }
    }

    "recover and continue after passivation" in { f: Fixture =>
      import f._
      import scala.concurrent.ExecutionContext.Implicits.global

      val p1 = State( tid, Foo( tid, "foo", "foo", b = 3.14159 ) )
      entityRef !+ protocol.Bar( p1.id, p1.foo.b )
      bus.expectMsgClass( classOf[protocol.Barred] )
      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, p1 )
      }

      val barredType = TypeCase[protocol.Barred]
      val p2 = FooModule.FooActor.foob.set( p1 )( 1.2345 ).copy( count = 2 )
      entityRef !+ protocol.Bar( p2.id, p2.foo.b )
      bus.expectMsgPF( 1.second, "Post Passivation BAR" ) {
        case barredType( protocol.Barred( pid, b ) ) => {
          pid mustBe p2.id
          b mustBe p2.foo.b
        }
      }
      whenReady( infoFrom( entityRef ) ) { _.value mustBe p2 }

      logger.debug( "TEST:SLEEPING..." )
      Thread.sleep( 3000 )
      logger.debug( "TEST:AWAKE..." )

      whenReady( infoFrom( entityRef ) ) { _.value mustBe p2 }

      val p3 = FooModule.FooActor.foob.set( p1 )( 12 ).copy( count = 3 )
      entityRef !+ protocol.Bar( p3.id, p3.foo.b )
      bus.expectMsgPF( 1.second.dilated, "Post Passivation BAR" ) {
        case barredType( protocol.Barred( pid, b ) ) => {
          pid mustBe p3.id
          b mustBe p3.foo.b
        }
      }

      whenReady( infoFrom( entityRef ) ) { _.value mustBe p3 }
    }
  }
}

object AggregateRootFunctionalSpec {

  trait Foo extends Entity with Equals {
    override type ID = ShortUUID

    def f: Int
    def b: Double
    def z: String

    override def hashCode(): Int = {
      41 * (
        41 * (
          41 * (
            41 * (
              41 * (
                41 + id.##
              ) + name.##
            ) + slug.##
          ) + f.##
        ) + b.##
      ) + z.##
    }

    override def equals( rhs: Any ): Boolean = {
      rhs match {
        case that: Foo => {
          if (this eq that) true
          else {
            (that.## == this.##) &&
            (that canEqual this) &&
            (this.id == that.id) &&
            (this.name == that.name) &&
            (this.slug == that.slug) &&
            (this.f == that.f) &&
            (this.b == that.b) &&
            (this.z == that.z)
          }
        }

        case _ => false
      }
    }
  }

  object Foo extends EntityLensProvider[Foo] {

    def apply(
      id: Foo#TID,
      name: String,
      slug: String,
      f: Int = 0,
      b: Double = 0.0,
      z: String = ""
    ): Foo = {
      FooImpl( id, name, slug, f, b, z )
    }

    implicit val fooIdentifying = new EntityIdentifying[Foo] {
      override def nextTID: ErrorOr[TID] = tag( ShortUUID() ).asRight
      override def idFromString( idRep: String ): ShortUUID = ShortUUID fromString idRep
    }

    override val idLens: Lens[Foo, Foo#TID] = new Lens[Foo, Foo#TID] {
      override def get( f: Foo ): Foo#TID = f.id
      override def set( f: Foo )( id: Foo#TID ): Foo = {
        FooImpl( id = id, name = f.name, slug = f.slug, f = f.f, b = f.b, z = f.z )
      }
    }

    override val nameLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.name
      override def set( f: Foo )( n: String ): Foo = {
        FooImpl( id = f.id, name = n, slug = f.slug, f = f.f, b = f.b, z = f.z )
      }
    }

    val slugLens: Lens[Foo, String] = new Lens[Foo, String] {
      override def get( f: Foo ): String = f.slug
      override def set( f: Foo )( s: String ): Foo = {
        FooImpl( id = f.id, name = f.name, slug = s, f = f.f, b = f.b, z = f.z )
      }
    }

    val bLens: Lens[Foo, Double] = new Lens[Foo, Double] {
      override def get( f: Foo ): Double = f.b
      override def set( f: Foo )( newB: Double ): Foo =
        Foo( id = f.id, name = f.name, slug = f.slug, f = f.f, b = newB, z = f.z )
    }

    final case class FooImpl(
      override val id: Foo#TID,
      override val name: String,
      override val slug: String,
      override val f: Int = 0,
      override val b: Double = 0.0,
      override val z: String = ""
    ) extends Foo {
      override def canEqual( that: Any ): Boolean = that.isInstanceOf[FooImpl]
      override def toString(): String =
        s"""FooImpl(id=$id, name=$name, slug=$slug, f=$f, b=$b, z="$z")"""
    }
  }

  object Protocol extends AggregateProtocol[State#ID] {
    case class LogWarning( override val targetId: LogWarning#TID, message: String ) extends Command
    case class Bar( override val targetId: Bar#TID, b: Double ) extends Command
    case class Barred( override val sourceId: Barred#TID, b: Double ) extends Event
    case class GetState( override val targetId: GetState#TID ) extends Command
    case class MyState( override val sourceId: MyState#TID, state: Option[State] ) extends Event
  }

  case class State( id: TaggedID[ShortUUID], foo: Foo, count: Int = 1 ) {
    type ID = State.stateIdentifying.ID
  }

  object State {
    implicit val stateIdentifying = new Identifying[State]
    with ShortUUID.ShortUuidIdentifying[State] {
      override val idTag: Symbol = Symbol( "foo-state" )
      override def tidOf( o: State ): TID = o.id
    }
  }

  object FooModule extends AggregateRootModule[State, ShortUUID] { module =>
    private def trace: Trace[_] = Trace[FooModule.type]

    override type ID = ShortUUID

    override val rootType: AggregateRootType = trace.block( "rootType" ) {
      new AggregateRootType {
        override def repositoryProps( implicit model: DomainModel ): Props = {
          CommonLocalRepository.props( model, this, FooActor.props( _, _ ) )
        }

        override def name: String = FooModule.shardName
        override type S = State
        override val identifying: Identifying[State] = State.stateIdentifying
        //      override def aggregateRootProps( implicit model: DomainModel ): Props = FooModule.FooActor.props( model, this )
        override val toString: String = "FooAggregateRootType"
        override def passivateTimeout: Duration = 1.seconds
      }
    }

    object FooActor {

      def props( model: DomainModel, rootType: AggregateRootType ): Props = {
        Props( new FooActor( model, rootType ) with StackableStreamPublisher )
      }

      val fooLens: Lens[State, Foo] = lens[State] >> 'foo
      val countLens: Lens[State, Int] = lens[State] >> 'count
      val foob: Lens[State, Double] = Foo.bLens compose fooLens
      val fooAndCount = foob ~ countLens

    }

    class FooActor(
      override val model: DomainModel,
      override val rootType: AggregateRootType
    ) extends AggregateRoot[Option[State], ShortUUID]()(
          Identifying.optionIdentifying( State.stateIdentifying ),
          classTag[Option[State]]
        )
        with AggregateRoot.Provider { outer: EventPublisher =>

      override val acceptance: Acceptance = {
        case ( Protocol.Barred( i, b ), s ) if s.isDefined => {
          val oid = outer.id
          log.debug(
            "TEST: oid:[{}] accepted BARRED i=[{}] b=[{}]  current-state:[{}]",
            oid,
            i,
            b,
            s
          )
          val result = s map { cur =>
            FooActor.fooAndCount.modify( cur ) { case ( _, count ) => ( b, count + 1 ) }
          }
          log.info( "TEST[{}]: UPDATED STATE = [{}]", result.map { _.count }, result )
          result
        }

        case ( Protocol.Barred( i, b ), s ) => {
          val oid = outer.id
          log.debug(
            "TEST: oid:[{}] accepted BARRED id:[{}] b=[{}]  current-state:[{}]",
            oid,
            i,
            b,
            s
          )
          Option( State( id = oid, foo = Foo( oid, "foo", "foo", b = b ) ) )
        }
      }

      val id: TID = aggregateId

      override var state: Option[State] = None

      override def receiveCommand: Receive = LoggingReceive { around( action ) }

      val action: Receive = {
        case m: Protocol.LogWarning => {
          log.warning(
            "TEST_LOG WARNING @ {}: {} akka-loggers:[{}]",
            System.currentTimeMillis(),
            m.message,
            AggregateRootFunctionalSpec.config.getStringList( "akka.loggers" )
          )
        }
//        case ReceiveTimeout => log.debug( "TEST: GOT RECEIVE_TIMEOUT MESSAGE!!!" )
        case m @ Protocol.Bar( id, b ) => {
          log.debug( "TEST: received [{}]", m )
          persist( Protocol.Barred( id, b ) ) { acceptAndPublish }
        }
        case m: Protocol.GetState => {
          log.debug( "TEST: received [{}]", m )
          log.debug( "TEST: context receiveTimeout: [{}]", context.receiveTimeout )
          sender() ! Protocol.MyState( id, state )
        }
      }
    }
  }

  val config: Config = ConfigFactory.parseString(
    """
      |akka.persistence {
      |  journal {
      |#    plugin = "akka.persistence.journal.leveldb-shared"
      |    plugin = "akka.persistence.journal.leveldb"
      |    leveldb-shared.store {
      |      # DO NOT USE 'native = off' IN PRODUCTION !!!
      |      native = off
      |      dir = "core/target/shared-journal"
      |    }
      |    leveldb {
      |      # DO NOT USE 'native = off' IN PRODUCTION !!!
      |      native = off
      |      dir = "core/target/persistence/journal"
      |    }
      |  }
      |  snapshot-store {
      |    plugin = "akka.persistence.snapshot-store.local"
      |    local.dir = "core/target/persistence/snapshots"
      |  }
      |}
      |
      |akka {
      |  loggers = ["akka.event.slf4j.Slf4jLogger", "akka.testkit.TestEventListener"]
      |
      |  logging-filter = "akka.event.DefaultLoggingFilter"
      |  loglevel = DEBUG
      |  stdout-loglevel = "DEBUG"
      |  log-dead-letters = on
      |  log-dead-letters-during-shutdown = on
      |
      |  actor {
      |    provider = "akka.cluster.ClusterActorRefProvider"
      |  }
      |
      |  remote {
      |    log-remote-lifecycle-events = off
      |    netty.tcp {
      |      hostname = "127.0.0.1"
      |      port = 0
      |    }
      |  }
      |
      |  cluster {
      |    seed-nodes = [
      |      "akka.tcp://ClusterSystem@127.0.0.1:2551",
      |      "akka.tcp://ClusterSystem@127.0.0.1:2552"
      |    ]
      |
      |    auto-down-unreachable-after = 10s
      |  }
      |}
      |
      |akka {
      |  extensions = ["com.romix.akka.serialization.kryo.KryoSerializationExtension$"]
      |  actor {
      |    kryo  {
      |      type = "graph"
      |      idstrategy = "incremental"
      |      buffer-size = 4096
      |      max-buffer-size = -1
      |      use-manifests = false
      |      use-unsafe = false
      |      post-serialization-transformations = "lz4,aes"
      |      encryption {
      |        aes {
      |          mode = "AES/CBC/PKCS5Padding"
      |          key = "5ZQq!7FbW&SNqepm"
      |          IV-length = 16
      |        }
      |      }
      |      implicit-registration-logging = true
      |      kryo-trace = false
      |      resolve-subclasses = false
      |      //      mappings {
      |      //        "package1.name1.className1" = 20,
      |      //        "package2.name2.className2" = 21
      |      //      }
      |      classes = [
      |        "demesne.EventLike"
      |      ]
      |    }
      |
      |    serializers {
      |      java = "akka.serialization.JavaSerializer"
      |      kyro = "com.romix.akka.serialization.kryo.KryoSerializer"
      |    }
      |
      |    serialization-bindings {
      |      "demesne.EventLike" = kyro
      |      "scala.Option" = kyro
      |    }
      |  }
      |}
      |
      |akka.actor.debug {
      |  # enable function of Actor.loggable(), which is to log any received message
      |  # at DEBUG level, see the “Testing Actor Systems” section of the Akka
      |  # Documentation at http://akka.io/docs
      |  receive = on
      |
      |  # enable DEBUG logging of all AutoReceiveMessages (Kill, PoisonPill et.c.)
      |  autoreceive = on
      |
      |  # enable DEBUG logging of actor lifecycle changes
      |  lifecycle = on
      |
      |  # enable DEBUG logging of all LoggingFSMs for events, transitions and timers
      |  fsm = on
      |
      |  # enable DEBUG logging of subscription changes on the eventStream
      |  event-stream = on
      |
      |  # enable DEBUG logging of unhandled messages
      |  unhandled = on
      |
      |  # enable WARN logging of misconfigured routers
      |  router-misconfiguration = on
      |}
      |
      |demesne.index-dispatcher {
      |  type = Dispatcher
      |  executor = "fork-join-executor"
      |  fork-join-executor {
      |    # Min number of threads to cap factor-based parallelism number to
      |    parallelism-min = 2
      |    # Parallelism (threads) ... ceil(available processors * factor)
      |    parallelism-factor = 2.0
      |    # Max number of threads to cap factor-based parallelism number to
      |    parallelism-max = 10
      |  }
      |  # Throughput defines the maximum number of messages to be
      |  # processed per actor before the thread jumps to the next actor.
      |  # Set to 1 for as fair as possible.
      |  throughput = 100
      |}
    """.stripMargin
  )
}
