package demesne

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.serialization.SerializationExtension
import akka.testkit._
import com.romix.akka.serialization.kryo.KryoSerializer
import org.scalatest.concurrent.ScalaFutures
import shapeless._
import com.typesafe.config.{ Config, ConfigFactory }
import demesne.repository.CommonLocalRepository
import org.scalatest.{ OptionValues, TryValues }
import omnibus.akka.publish.{ EventPublisher, StackableStreamPublisher }
import omnibus.archetype.domain.model.core.{ Entity, EntityLensProvider }
import omnibus.akka.envelope._
import omnibus.identifier.{ Id, Identifying, Labeling, ShortUUID }
import AggregateRootFunctionalSpec.{ AggregateState, FooState }
import com.twitter.chill.akka.AkkaSerializer

import scala.reflect.ClassTag
import scala.util.Try

/**
  * Created by rolfsd on 6/29/16.
  */
class AggregateRootFunctionalSpec
    extends demesne.testkit.AggregateRootSpec[AggregateState, FooState.identifying.ID]
    with ScalaFutures
    with OptionValues
    with TryValues {
  import AggregateRootFunctionalSpec._

  override def configurationForTest( test: OneArgTest, slug: String ): Option[Config] = {
//    import net.ceedubs.ficus.Ficus._
//    scribe.debug(
//      s"DMR: akka.cluster = [${AggregateRootFunctionalSpec.config.as[Config]( "akka.cluster" ).toString}]"
//    )
    Option( AggregateRootFunctionalSpec.config )
  }

  override def createAkkaFixture( test: OneArgTest, system: ActorSystem, slug: String ): Fixture = {
    new Fixture( slug, system )
  }

//  implicit val id2: Identifying[State] = State.identifying
  override type Protocol = AggregateRootFunctionalSpec.Protocol.type
  override val protocol: Protocol = AggregateRootFunctionalSpec.Protocol

  class Fixture( _slug: String, _system: ActorSystem ) extends AggregateFixture( _slug, _system ) {

    override val module: AggregateRootModule[State, FooState.identifying.ID] = {
      AggregateRootFunctionalSpec.FooModule
    }

    val fooTid: Foo#TID = AggregateRootFunctionalSpec.Foo.identifying.next

//    override type State = AggregateRootFunctionalSpec.State
//    override type ID = AggregateRootFunctionalSpec.Foo#ID

    override def rootTypes: Set[AggregateRootType] = {
      Set( AggregateRootFunctionalSpec.FooModule.rootType )
    }

    def infoFrom( ar: ActorRef )( implicit ec: ExecutionContext ): Future[AggregateState] = {
      import akka.pattern.ask
      (ar ? protocol.GetState( tid )).mapTo[protocol.MyState].map { _.state }
    }
  }

  def assertStates( actual: FooState, expected: FooState ): Unit = {
    scribe.debug( s"asserting actual:[${actual}] == expected:[${expected}]" )
//    actual.id.tag.name mustBe expected.id.tag.name
    actual.id.value mustBe expected.id.value
    actual.id mustBe expected.id
    actual.foo.name mustBe expected.foo.name
    actual.foo.slug mustBe expected.foo.slug
    actual.foo.f mustBe expected.foo.f
    actual.foo.b mustBe expected.foo.b
    actual.foo.z mustBe expected.foo.z
    actual.foo.id mustBe expected.foo.id
    actual.foo.## mustBe expected.foo.##
    actual.count mustBe expected.count
    actual mustBe expected
  }

  "AggregateRoot" should {
    "kryo config is included" in { f: Fixture =>
      import f._
      import net.ceedubs.ficus.Ficus._
      system.settings.config.as[String]( "akka.actor.kryo.type" ) mustBe "graph"

      scribe.error( s"FooState FQCN = ${classOf[FooState].getName}" )
      scribe.error( s"Id[Option[FooState]] FQCN = ${classOf[Id[Option[FooState]]].getName}" )
      scribe.error( s"Id[FooState] FQCN = ${classOf[Id[FooState]].getName}" )
      classOf[Id[Option[FooState]]].getName mustBe "omnibus.identifier.Id"
    }

    "should get right serializers" in { f: Fixture =>
      import f._
      val serialization = SerializationExtension( system )
      val state = FooState( tid, Foo( fooTid, "foo", "bar", b = 19 ), count = 1 )

      val ofsId = Id.of[Option[FooState], Long]( 17L )
      val fsId = Id.of[FooState, Long]( 13L )
      serialization.findSerializerFor( state ).getClass mustBe classOf[AkkaSerializer]
      serialization.findSerializerFor( ofsId ).getClass mustBe classOf[AkkaSerializer]
      serialization.findSerializerFor( fsId ).getClass mustBe classOf[AkkaSerializer]
    }

    "id should be serializable" in { f: Fixture =>
      import f._
      val serialization = SerializationExtension( system )
      val ofsId = Id.of[Option[FooState], Long]( 23L )
      val fsId23 = Id.of[FooState, Long]( 23L )
      ofsId mustBe fsId23

      val fsId = Id.of[FooState, Long]( 29L )

      val ofsSerialized = serialization serialize ofsId
      ofsSerialized must be a 'success

      val fsSerialized = serialization serialize fsId
      fsSerialized must be a 'success

      val ofsDeserialized = serialization.deserialize(
        ofsSerialized.get,
        classOf[Id[Option[FooState]]]
      )
      ofsDeserialized must be a 'success
      ofsDeserialized.success.value mustBe ofsId

      val fsDeserialized = serialization.deserialize(
        fsSerialized.get,
        classOf[Id[FooState]]
      )
      fsDeserialized must be a 'success
      fsDeserialized.success.value mustBe fsId

//      val fsaux: Id.Aux[FooState, Long] = fsDeserialized.success.value
//      fsaux mustBe fsId
    }

    "tid in proper form" in { f: Fixture =>
      import f._
      scribe.info(
        s"DMR **** TID=[${tid.toString}] and fooTid=[${fooTid.toString}]"
      )
//      scribe.info(
//        s"DMR **** stateType=[${stateType.toString}]"
//      )

      "val id: Id.Aux[Option[FooState], Long] = tid" must compile

      scribe.error( s"tid = $tid; value = ${tid.value}" )
      tid.getClass.getName mustBe "omnibus.identifier.Id$Simple"
      tid.toString() mustBe s"FooStateId(${tid.value})"
    }

    "state is serializable" in { f: Fixture =>
      import f._
      val state = FooState( tid, Foo( fooTid, "foo", "bar", b = 19 ), count = 1 )
      val serialization = SerializationExtension( system )
      serialization.findSerializerFor( state ).getClass mustBe classOf[AkkaSerializer]
      val serialized = serialization serialize state
      serialized must be a 'success
      val hydrated = serialization.deserialize( serialized.success.value, classOf[FooState] )
      hydrated must be a 'success
      hydrated.success.value.id mustBe tid
      hydrated.success.value.foo.id mustBe fooTid
      hydrated.success.value mustBe state

      serialization.findSerializerFor( Option( state ) ).getClass mustBe classOf[AkkaSerializer]
      val aserialized = serialization serialize Option( state )
      aserialized must be a 'success
      val ahydrated =
        serialization.deserialize( aserialized.success.value, classOf[AggregateState] )
      ahydrated must be a 'success
      ahydrated.success.value.value.id mustBe tid
      ahydrated.success.value.value.foo.id mustBe fooTid
      ahydrated.success.value mustBe Option( state )
    }

    "optional state is serializable" in { f: Fixture =>
      import f._
      scribe.info(
        s"DMR **** TID=[${tid.toString}] and fooTid=[${fooTid.toString}]"
      )
      val state = Option( FooState( tid, Foo( fooTid, "foo", "bar", b = 19 ), count = 1 ) )
      scribe.info(
        s"DMR **** STATE's tid=[${state.get.id.toString}] and fooTid=[${state.get.foo.id.toString}]"
      )
      val serialization = SerializationExtension( system )
//      serialization.findSerializerFor( state ).getClass mustBe classOf[AkkaSerializer]
      val serialized = serialization serialize state.value
      serialized must be a 'success
      val hydrated: Try[FooState] = serialization.deserialize(
        serialized.success.value,
        classOf[FooState]
      )

      val atid = hydrated.success.value.id
      val aftid = hydrated.success.value.foo.id
      scribe.info(
        s"DMR **** HYDRATED's tid=[${atid.toString}] and fooTid=[${aftid.toString()}]"
      )
      hydrated must be a 'success
      hydrated.success.value mustBe state.value
      hydrated.success.value.id mustBe tid
      val hfsid: FooState#TID = hydrated.success.value.id
      hfsid.toString() mustBe s"FooStateId(${tid.value})"
      val hfid: Foo#TID = hydrated.success.value.foo.id
      hfid.toString() mustBe s"FooId(${fooTid.value})"
      hfid mustBe fooTid
    }

    "protocol events are serializable" in { f: Fixture =>
      import f._
      scribe.info(
        s"DMR **** TID=[${tid.toString}] and fooTid=[${fooTid.toString}]"
      )
      val barred = protocol.Barred( sourceId = tid, b = 3.14159 )
      scribe.info( s"DMR **** barred's sourceId=[${barred.sourceId}]" )
      val serialization = SerializationExtension( system )
      serialization.findSerializerFor( barred ).getClass mustBe classOf[AkkaSerializer]
      val serialized = serialization serialize barred
      serialized must be a 'success
      val hydrated = serialization.deserialize(
        serialized.success.value,
        classOf[protocol.Barred]
      )
      scribe.info(
        s"DMR **** HYDRATED barred=[${hydrated.success.value}] and barred.sourceTid=[${hydrated.success.value.sourceId}]"
      )
      hydrated must be a 'success
      hydrated.success.value mustBe barred
      hydrated.success.value.sourceId mustBe tid
      val hfsid: FooState#TID = hydrated.success.value.sourceId
      hfsid.toString() mustBe s"FooStateId(${tid.value})"
    }

    "save and reload a snapshot" in { f: Fixture =>
      import f._
      import scala.concurrent.ExecutionContext.Implicits.global

      val e1 = FooState( tid, Foo( fooTid, "foo", "foo", b = 17 ), count = 1 )
      entityRef !+ protocol.Bar( e1.id, e1.foo.b )
      bus.expectMsgClass( classOf[protocol.Barred] )
      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, e1.withFooId( actual.value.foo.id ) )
      }

      val e2 = FooState( tid, Foo( fooTid, "foo", "foo", b = 3.14159 ), count = 2 )

      EventFilter.debug( start = "aggregate snapshot successfully saved:", occurrences = 1 ) intercept {
        scribe.debug( "+ INTERCEPT" )
        entityRef !+ FooModule.rootType.snapshot.value.saveSnapshotCommand( tid )
        entityRef !+ protocol.Bar( e2.id, e2.foo.b )
        scribe.debug( "TEST:SLEEPING..." )
        Thread.sleep( 3000 )
        scribe.debug( "TEST:AWAKE..." )
        scribe.debug( "- INTERCEPT" )
      }

      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, e2.withFooId( actual.value.foo.id ) )
      }
    }

    "recover and continue after passivation" taggedAs WIP in { f: Fixture =>
      import f._
      import scala.concurrent.ExecutionContext.Implicits.global

      val p1 = FooState( tid, Foo( fooTid, "foo", "foo", b = 3.14159 ) )
      entityRef !+ protocol.Bar( p1.id, p1.foo.b )
      bus.expectMsgClass( classOf[protocol.Barred] )
      whenReady( infoFrom( entityRef ) ) { actual =>
        assertStates( actual.value, p1.withFooId( actual.value.foo.id ) )
      }

      import scala.reflect.classTag
      val BarredType = classTag[protocol.Barred]
      val p2 = FooModule.FooActor.foob.set( p1 )( 1.2345 ).copy( count = 2 )
      entityRef !+ protocol.Bar( p2.id, p2.foo.b )
      bus.expectMsgPF( 1.second, "Post Passivation BAR" ) {
        case BarredType( protocol.Barred( pid, b ) ) => {
          pid mustBe p2.id
          b mustBe p2.foo.b
        }
      }
      whenReady( infoFrom( entityRef ) ) { a =>
        a.value mustBe p2.withFooId( a.value.foo.id )
      }

      scribe.debug( "TEST:SLEEPING..." )
      Thread.sleep( 3000 )
      scribe.debug( "TEST:AWAKE..." )

      whenReady( infoFrom( entityRef ) ) { a =>
        a.value mustBe p2.withFooId( a.value.foo.id )
      }

      val p3 = FooModule.FooActor.foob.set( p1 )( 12 ).copy( count = 3 )
      entityRef !+ protocol.Bar( p3.id, p3.foo.b )
      bus.expectMsgPF( 1.second.dilated, "Post Passivation BAR" ) {
        case BarredType( protocol.Barred( pid, b ) ) => {
          pid mustBe p3.id
          b mustBe p3.foo.b
        }
      }

      whenReady( infoFrom( entityRef ) ) { a =>
        a.value mustBe p3.withFooId( a.value.foo.id )
      }
    }
  }
}

object AggregateRootFunctionalSpec {
  type AggregateState = Option[FooState]

//  object AggregateState {
//    implicit val optionalIdentifying: Identifying.Aux[Option[FooState], Long] =
//      Identifying.toComposite[Option, FooState, Long]( FooState.identifying )
//  implicit val optionalIdentifying = Identifying.optionalIdentifying
//  }

  case class FooState( id: FooState#TID, foo: Foo, count: Int = 1 ) {
    type TID = FooState.identifying.TID
  }

  object FooState {
    implicit val identifying: Identifying.Aux[FooState, Long] = new Identifying[FooState] {
      override type ID = Long
      override protected def tag( value: ID ): TID = {
        Id.of[FooState, ID]( value )( this, Labeling[FooState] )
      }
      override def label: String = Labeling[FooState].label
      override def zeroValue: ID = 0L
      override def nextValue: ID = scala.util.Random.nextLong()
      override def valueFromRep( rep: String ): ID = rep.toLong
    }

  }

  implicit class FooIdSync( val underlying: FooState ) extends AnyVal {

    def withFooId( fid: Foo#TID ): FooState = {
      underlying.copy( foo = Foo.idLens.set( underlying.foo )( fid ) )
    }
  }

  trait Foo extends Entity[Foo, ShortUUID] with Equals {
//    override type E = Foo

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

  object Foo extends EntityLensProvider[Foo, ShortUUID] {

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

    override implicit val identifying: Identifying.Aux[Foo, ShortUUID] = Identifying.byShortUuid

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

  object Protocol extends AggregateProtocol[AggregateState, Long] {
    case class LogWarning( override val targetId: LogWarning#TID, message: String ) extends Command
    case class Bar( override val targetId: Bar#TID, b: Double ) extends Command
    case class Barred( override val sourceId: Barred#TID, b: Double ) extends Event
    case class GetState( override val targetId: GetState#TID ) extends Command
    case class MyState( override val sourceId: MyState#TID, state: AggregateState ) extends Event
  }

  object FooModule extends AggregateRootModule[AggregateState, FooState.identifying.ID] { module =>
    override val rootType: AggregateRootType = {
      new AggregateRootType {
        override def repositoryProps( implicit model: DomainModel ): Props = {
          CommonLocalRepository.props( model, this, FooActor.props( _, _ ) )
        }

        override def name: String = FooModule.shardName
        override type S = AggregateState
//        override val identifying: Identifying[S] = Identifying[S]
        //      override def aggregateRootProps( implicit model: DomainModel ): Props = FooModule.FooActor.props( model, this )
        override val toString: String = "FooAggregateRootType"
        override def passivateTimeout: Duration = 1.seconds
      }
    }

    object FooActor {

      def props( model: DomainModel, rootType: AggregateRootType ): Props = {
        Props( new FooActor( model, rootType ) with StackableStreamPublisher )
      }

      val fooLens: Lens[FooState, Foo] = lens[FooState] >> 'foo
      val countLens: Lens[FooState, Int] = lens[FooState] >> 'count
      val foob: Lens[FooState, Double] = Foo.bLens compose fooLens
      val fooAndCount = foob ~ countLens

    }

    class FooActor(
      override val model: DomainModel,
      override val rootType: AggregateRootType
    ) extends AggregateRoot[AggregateState, FooState.identifying.ID]
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

          Option( FooState( id = oid, foo = Foo( Foo.nextId, "foo", "foo", b = b ) ) )
        }
      }

      val id: TID = aggregateId

      override var state: AggregateState = None

      override def receiveCommand: Receive = LoggingReceive { around( action ) }

      val action: Receive = LoggingReceive {
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
          val myState = Protocol.MyState( id, state )
          log.debug( "TEST: received [{}] replying with state:[{}]", m, myState )
          log.debug( "TEST: context receiveTimeout: [{}]", context.receiveTimeout )
          sender() ! myState
        }
      }
    }
  }

  val config: Config = ConfigFactory.parseString(
    s"""
      |include "kryo"
      |#akka.actor.kryo.idstrategy = automatic
      |
      |#akka.actor.serialization-bindings {
      |#  "${classOf[FooState].getName}" = kryo
      |#  "scala.Option" = kryo
      |#}
      |
      |akka.persistence {
      |  journal {
      |#  plugin = "akka.persistence.journal.inmem"
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
      |#    provider = "cluster"
      |  }
      |
      |#  remote {
      |#    log-remote-lifecycle-events = off
      |#    netty.tcp {
      |#      hostname = "127.0.0.1"
      |#      port = 0
      |#    }
      |#  }
      |
      |#  cluster {
      |#    seed-nodes = [
      |#      "akka.tcp://ClusterSystem@127.0.0.1:2551",
      |#      "akka.tcp://ClusterSystem@127.0.0.1:2552"
      |#    ]
      |#
      |#    auto-down-unreachable-after = 10s
      |#  }
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
