package demesne

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect._
import akka.actor.{ActorRef, Props}
import akka.event.LoggingReceive
import akka.testkit._
import scalaz.Scalaz._
import shapeless._
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.OptionValues
import peds.akka.publish.{EventPublisher, StackableStreamPublisher}
import peds.archetype.domain.model.core.{Entity, EntityIdentifying, EntityLensProvider}
import peds.akka.envelope._
import peds.commons.TryV
import peds.commons.identifier.{ShortUUID, TaggedID}
import peds.commons.log.Trace


/**
  * Created by rolfsd on 6/29/16.
  */
class AggregateRootFunctionalSpec extends demesne.testkit.AggregateRootSpec[AggregateRootFunctionalSpec] with OptionValues {
  import AggregateRootFunctionalSpec._

  override type ID = Foo#ID

  override type Protocol = AggregateRootFunctionalSpec.Protocol.type
  override val protocol: Protocol = AggregateRootFunctionalSpec.Protocol


  class Fixture extends AggregateFixture( config = AggregateRootFunctionalSpec.config ) {
    override val module: AggregateRootModule = AggregateRootFunctionalSpec.FooModule
    override def moduleCompanions: List[AggregateRootModule] = List( AggregateRootFunctionalSpec.FooModule )

    override def nextId(): TID = Foo.fooIdentifying.safeNextId


    override implicit def before( test: OneArgTest ): Unit = super.before( test )  //todo: module initialize and context() happens in testkit.AggregateRootSpec.AggregateFixture.before

    override def context: Map[Symbol, Any] = {
      Map(
        demesne.ModelKey -> model,
        demesne.SystemKey -> system,
        demesne.FactoryKey -> demesne.factory.contextFactory
//        demesne.FactoryKey -> demesne.factory.clusteredFactory
      )
    }
  }

  override def createAkkaFixture( test: OneArgTest ): Fixture = new Fixture


  "AggregateRoot" should {
    "recover and continue after passivation" taggedAs WIP in { f: Fixture =>
      import f._
      import FooModule.FooActor.State

      def infoFrom( ar: ActorRef ): Option[State] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        import akka.pattern.ask
        Await.result(
          ( ar ? protocol.GetState(tid) ).mapTo[protocol.MyState].map{ _.state },
          2.seconds.dilated
        )
      }

      val p1 = State( tid, Foo(tid, "foo", "foo", b = 3.14159) )
      entityRef !+ protocol.Bar( p1.id, p1.foo.b )
      bus.expectMsgClass( classOf[protocol.Barred] )
      val e1 = infoFrom( entityRef ).value
      e1.id mustBe p1.id
      e1.foo.name mustBe p1.foo.name
      e1.foo.slug mustBe p1.foo.slug
      e1.foo.f mustBe p1.foo.f
      e1.foo.b mustBe p1.foo.b
      e1.foo.z mustBe p1.foo.z
      e1.foo.## mustBe p1.foo.##
      e1 mustBe p1

      val barredType = TypeCase[protocol.Barred]
      val p2 = FooModule.FooActor.foob.set(p1)(1.2345)
      entityRef !+ protocol.Bar( p2.id, p2.foo.b )
      bus.expectMsgPF( 1.second, "Post Passivation BAR" ) {
        case barredType(protocol.Barred(pid, b)) => {
          pid mustBe p2.id
          b mustBe p2.foo.b
        }
      }
      infoFrom( entityRef ).value mustBe p2

      logger.info( "TEST:SLEEPING...")
      Thread.sleep( 5000 )
      logger.info( "TEST:AWAKE...")

      infoFrom( entityRef ).value mustBe p2

      val p3 = FooModule.FooActor.foob.set(p1)(12)
      entityRef !+ protocol.Bar( p3.id, p3.foo.b )
      bus.expectMsgPF( 1.second.dilated, "Post Passivation BAR" ) {
        case barredType(protocol.Barred(pid, b)) => {
          pid mustBe p3.id
          b mustBe p3.foo.b
        }
      }

      infoFrom( entityRef ).value mustBe p3
    }
  }
}

object AggregateRootFunctionalSpec {
  trait Foo extends Entity with Equals {
    override type ID = ShortUUID
    override val evID: ClassTag[ID] = classTag[ShortUUID]
    override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]

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
          if ( this eq that ) true
          else {
            ( that.## == this.## ) &&
            ( that canEqual this ) &&
            ( this.id == that.id ) &&
            ( this.name == that.name ) &&
            ( this.slug == that.slug ) &&
            ( this.f == that.f ) &&
            ( this.b == that.b ) &&
            ( this.z == that.z )
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
      override val evEntity: ClassTag[Foo] = classTag[Foo]
      override val evID: ClassTag[ID] = classTag[ShortUUID]
      override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]
      override def nextId: TryV[TID] = tag( ShortUUID() ).right
      override def fromString( idstr: String ): ShortUUID = {
        logger.info( "fooIdentifying.fromString({}) = [{}]", idstr, ShortUUID(idstr) )
        ShortUUID( idstr )
      }
    }

    override val idLens: Lens[Foo, Foo#TID] = new Lens[Foo,  Foo#TID] {
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
      override def set( f: Foo )( newB: Double ): Foo = Foo( id = f.id, name = f.name, slug = f.slug, f = f.f, b = newB, z = f.z )
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
      override def toString(): String = s"""FooImpl(id=$id, name=$name, slug=$slug, f=$f, b=$b, z="$z")"""
    }
  }


  object Protocol extends AggregateProtocol[Foo#ID] {
    case class Bar( override val targetId: Bar#TID, b: Double ) extends Command
    case class Barred( override val sourceId: Barred#TID, b: Double ) extends Event
    case class GetState( override val targetId: GetState#TID ) extends Command
    case class MyState( override val sourceId: MyState#TID, state: Option[FooModule.FooActor.State] ) extends Event
  }

  object FooModule extends AggregateRootModule with CommonInitializeAggregateActorType { module =>
    override def trace: Trace[_] = Trace[FooModule.type]

    override type ID = ShortUUID
    override def nextId: TryV[TID] = Foo.fooIdentifying.nextIdAs[TID]

    override val rootType: AggregateRootType = new AggregateRootType {
      override def name: String = FooModule.shardName
      override def aggregateRootProps( implicit model: DomainModel ): Props = FooModule.FooActor.props( model, this )
      override val toString: String = "ALTERED_FooAggregateRootType"
      override def passivateTimeout: Duration = 2.seconds
    }



    object FooActor {
      def props( model: DomainModel, rootType: AggregateRootType ): Props = {
        Props( new FooActor( model, rootType ) with StackableStreamPublisher )
      }


      case class State( id: TaggedID[ShortUUID], foo: Foo )
      val fooLens: Lens[State, Foo] = lens[State] >> 'foo
      val foob = Foo.bLens compose fooLens
    }

    class FooActor(
      override val model: DomainModel,
      override val rootType: AggregateRootType
    ) extends AggregateRoot[Option[FooActor.State], ShortUUID] { outer: EventPublisher =>
      import FooActor.State

      override val acceptance: Acceptance = {
        case (Protocol.Barred(id, b), s) if s.isDefined => {
          log.info( "TEST: accepted BARRED b=[{}]  current-state:[{}]", b, s )
          s map { cur => FooActor.foob.set( cur )( b ) }
        }
        case (Protocol.Barred(id, b), s) => {
          log.info( "TEST: accepted BARRED b=[{}]  current-state:[{}]", b, s )
          val id = outer.id
          Option( State( id = id, foo = Foo(id, "foo", "foo", b = b) ) )
        }
      }

      override def parseId( idstr: String ): FooActor#TID = Foo.fooIdentifying.safeParseId[ShortUUID]( idstr )

      val id: TID = {
        logger.info( "TEST:BEFORE safeParseId:::: idFromPath=[{}]", idFromPath() )
        val i = Foo.fooIdentifying.safeParseId[ShortUUID]( idFromPath() )
        logger.info( "TEST: i = [{}], classOf(i)=[{}]", i, i.getClass.getCanonicalName )
        Foo.fooIdentifying.tag( i )
      }
      override var state: Option[State] = None


      override def receiveCommand: Receive = LoggingReceive { around( action ) }
      val action: Receive = {
//        case ReceiveTimeout => log.info( "TEST: GOT RECEIVE_TIMEOUT MESSAGE!!!" )
        case m @ Protocol.Bar(id, b) => {
          log.info( "TEST: received [{}]", m )
          persist( Protocol.Barred(id, b) ) { acceptAndPublish }
        }
        case m: Protocol.GetState => {
          log.info( "TEST: received [{}]", m )
          log.info( "TEST: context receiveTimeout: [{}]", context.receiveTimeout )
          sender() ! Protocol.MyState( id, state )
        }
      }
    }
  }


  val config: Config = ConfigFactory.parseString(
    """
      |akka.loggers = ["akka.testkit.TestEventListener"]
      |
      |akka.persistence {
      |#  journal.plugin = "akka.persistence.journal.leveldb-shared"
      |  journal.plugin = "akka.persistence.journal.leveldb"
      |  journal.leveldb-shared.store {
      |    # DO NOT USE 'native = off' IN PRODUCTION !!!
      |    native = off
      |    dir = "target/shared-journal"
      |  }
      |  journal.leveldb {
      |    # DO NOT USE 'native = off' IN PRODUCTION !!!
      |    native = off
      |    dir = "target/journal"
      |  }
      |  snapshot-store.local.dir = "target/snapshots"
      |}
      |
      |#akka {
      |#  persistence {
      |#    journal.plugin = "inmemory-journal"
      |#    snapshot-store.plugin = "inmemory-snapshot-store"
      |#
      |#    journal.plugin = "akka.persistence.journal.leveldb"
      |#    journal.leveldb.dir = "target/journal"
      |#    journal.leveldb.native = off
      |#    snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |#    snapshot-store.local.dir = "target/snapshots"
      |#  }
      |#}
      |
      |akka {
      |  loggers = ["akka.event.slf4j.Slf4jLogger"]
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
