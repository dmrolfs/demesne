package demesne.index

import scala.concurrent.duration._
import scala.reflect._
import akka.testkit._

import scalaz._
import Scalaz._
import org.scalatest.concurrent.ScalaFutures
import peds.akka.envelope._
import peds.commons.identifier._
import peds.commons.log.Trace
import peds.archetype.domain.model.core.Entity
import peds.commons.TryV
import demesne._
import demesne.index.local.IndexLocalAgent
import demesne.testkit.{AggregateRootSpec, SimpleTestModule}
import demesne.testkit.concurrent.CountDownFunction


object IndexAcceptanceSpec {
  case class Foo( override val id: TaggedID[ShortUUID], override val name: String, foo: String, bar: Int ) extends Entity {
    override type ID = ShortUUID
    override val evID: ClassTag[ID] = classTag[ShortUUID]
    override val evTID: ClassTag[TID] = classTag[TaggedID[ShortUUID]]
    def toSummary: Foo.Summary = Foo.Summary( this.id, Foo.Summary.foobar(this.foo, this.bar) )
  }

  object Foo {
    def summarize( foo: Foo ): Summary = Summary( foo.id, Summary.foobar(foo.foo, foo.bar) )
    case class Summary( id: Foo#TID, foobar: String )
    object Summary {
      def foobar( foo: String, bar: Int ): String = foo + "-" + bar
    }

    implicit lazy val fooIdentifying: Identifying[Foo] = new Identifying[Foo] {
      override type ID = Foo#ID
      override val idTag: Symbol = 'foo
      override def idOf( o: Foo ): TID = o.id
      override lazy val evID: ClassTag[ID] = ClassTag( classOf[ShortUUID] )
      override lazy val evTID: ClassTag[TID] = ClassTag( classOf[TID] )
      override def nextId: TryV[TID] = tag( ShortUUID() ).right
      override def fromString( idstr: String ): ID = ShortUUID( idstr )
    }
  }


  object Protocol extends AggregateProtocol[Foo#ID] {
    case class Add( override val targetId: Add#TID, name: String, foo: String, bar: Int ) extends Command
    case class ChangeBar( override val targetId: ChangeBar#TID, bar: Int ) extends Command
    case class Delete( override val targetId: Delete#TID ) extends Command

    case class Added( override val sourceId: Added#TID, name: String, foo: String, bar: Int ) extends Event
    case class BarChanged( override val sourceId: BarChanged#TID, name: String, oldBar: Int, newBar: Int ) extends Event
    case class Deleted( override val sourceId: Deleted#TID, name: String ) extends Event
  }


  object TestModule extends SimpleTestModule[Foo] { module =>
    override type ID = Foo#ID
    override lazy val evID: ClassTag[ID] = {

      implicitly[Identifying[Foo]].bridgeIdClassTag[ShortUUID] match {
        case \/-( ctag ) => {
          logger.info( "TestModule.evId = ctag = [{}]", ctag )
          ctag
        }
        case -\/( ex ) => {
          logger.error( "identifying couldn't provide sufficient ClassTag:", ex )
          throw ex
        }
      }
    }


    override def nextId: TryV[TID] = implicitly[Identifying[Foo]].nextIdAs[TID]


    override def parseId( idstr: String ): TID = {
      val identifying = implicitly[Identifying[Foo]]
      identifying.safeParseId[ID]( idstr )( evID )
    }

    override def eventFor( state: SimpleTestActor.State ): PartialFunction[Any, Any] = {
      case Protocol.Add( id, name, foo, bar ) => Protocol.Added( id, name, foo, bar )
      case Protocol.ChangeBar( id, newBar ) => {
        logger info s"state = $state"
        Protocol.BarChanged( id, state('name).asInstanceOf[String], state('bar).asInstanceOf[Int], newBar )
      }
      case Protocol.Delete( id ) => Protocol.Deleted( id, state('name).asInstanceOf[String] )
    }

    override def name: String = "RegisterAcceptance"

    override def indexes: Seq[IndexSpecification] = {
      Seq(
        IndexLocalAgent.spec[String, TestModule.TID, TestModule.TID]( 'bus, IndexBusSubscription ) {
          case Protocol.Added( sid, _, foo, _ ) => Directive.Record( foo, sid, sid )
          case Protocol.Deleted( sid, _ ) => Directive.Withdraw( sid )
        },
        IndexLocalAgent.spec[Int, TestModule.TID, TestModule.TID]( 'stream, ContextChannelSubscription( classOf[Protocol.Event] ) ) {
          case Protocol.Added( sid, _, _, bar ) => Directive.Record( bar, sid, sid )
          case Protocol.BarChanged( sid, name, oldBar, newBar ) => Directive.ReviseKey( oldBar, newBar )
          case Protocol.Deleted( sid, _ ) => Directive.Withdraw( sid )
        },
        IndexLocalAgent.spec[String, TestModule.TID, Foo.Summary]( 'summary, IndexBusSubscription ) {
          case Protocol.Added( sid, name, foo, bar ) => Directive.Record( name, sid, Foo.Summary(sid, Foo.Summary.foobar(foo, bar)) )
          case Protocol.BarChanged( sid, name, oldBar, newBar ) => {
            logger.info(
              "IndexLocalAgentSpec-summary: ALTERING sid:[{}] name:[{}] oldBar:[{}] newBar:[{}]",
              sid,
              name,
              oldBar.toString,
              newBar.toString
            )

            Directive.AlterValue[String, Foo.Summary]( name ) { oldSummary =>
              oldSummary.foobar
              .split( '-' )
              .headOption
              .map { foo => oldSummary.copy( foobar = Foo.Summary.foobar(foo, newBar) ) }
              .getOrElse { oldSummary }
            }
          }
          case Protocol.Deleted( sid, name ) => Directive.Withdraw( sid, Some(name) )
        }
      )
    }

    override val acceptance: AggregateRoot.Acceptance[SimpleTestActor.State] = {
      case ( Protocol.Added(id, name, foo, bar), state ) => {
        state + ( 'id -> id ) + ( 'name -> name ) + ( 'foo -> foo ) + ( 'bar -> bar )
      }
      case ( Protocol.BarChanged(id, _, _, newBar), state ) => state + ( 'bar -> newBar )
      case (_: Protocol.Deleted, _) => Map.empty[Symbol, Any]
    }
  }
}

class IndexAcceptanceSpec extends AggregateRootSpec[IndexAcceptanceSpec] with ScalaFutures {
  import IndexAcceptanceSpec._

  private val trace = Trace[IndexAcceptanceSpec]

  override type ID = Foo#ID
  override type Protocol = IndexAcceptanceSpec.Protocol.type
  override val protocol: Protocol = IndexAcceptanceSpec.Protocol


  override type Fixture = TestFixture

  class TestFixture extends AggregateFixture {
    override def nextId(): TID = {
      Foo.fooIdentifying.nextIdAs[TID] match {
        case \/-( r ) => r
        case -\/( ex ) => {
          logger.error( "failed to generate nextId", ex )
          throw ex
        }
      }
    }

    override val module: AggregateRootModule = TestModule

    override def rootTypes: Set[AggregateRootType] = Set( module.rootType )
  }

  override def createAkkaFixture( test: OneArgTest ): Fixture = new TestFixture

  "Index Index should" should {
    "recorded in registers after added" taggedAs WIP in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateIndexFor[String, TestModule.TID,  TestModule.TID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateIndexFor[Int, TestModule.TID, TestModule.TID]( rt, 'stream )
      sr.isRight mustBe true
      val sumReg = model.aggregateIndexFor[String, TestModule.TID, Foo.Summary]( rt, 'summary )
      sumReg.isRight mustBe true
      for {
        busRegister <- br 
        streamRegister <- sr
        summaryRegister <- sumReg
      } {
        val tid = TestModule.nextId.toOption.get
        val id = tid.id
        logger.info( "DMR: test tid =[{}]", tid )
        val name = "tfoo"
        val foo = "Test Foo"
        val bar = 17
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Added] )

        val aggregate = TestModule aggregateOf tid
        aggregate !+ Protocol.Add( tid, name, foo, bar )
        bus.expectMsgPF( hint = "added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe tid
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDown = new CountDownFunction[String]
        countDown await 200.millis.dilated

        whenReady( busRegister.futureGet( "Test Foo" ) ) { _ mustBe Some(tid) }
        trace( s"""bus-index:Test Foo = ${busRegister.get("Test Foo")}""" )
        busRegister.get( "Test Foo" ) mustBe Some(tid)

        whenReady( streamRegister.futureGet( 17 ) ) { _ mustBe Some(tid) }
        trace( s"stream-index:17 = ${streamRegister.get(17)}" )
        streamRegister.get( 17 ) mustBe Some(tid)

        whenReady( summaryRegister futureGet "tfoo" ) {
          _ mustBe Some(Foo.Summary(tid.asInstanceOf[Foo#TID], Foo.Summary.foobar(foo, bar)))
        }
      }
    }

    "withdrawn from index after delete" in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateIndexFor[String, TestModule.TID, TestModule.ID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateIndexFor[Int, TestModule.TID, TestModule.ID]( rt, 'stream )
      sr.isRight mustBe true
      val sumReg = model.aggregateIndexFor[String, TestModule.TID, Foo.Summary]( rt, 'summary )
      sumReg.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr
        summaryRegister <- sumReg
      } {
        val p = TestProbe()

        val id = TestModule.nextId.toOption.get
        val name = "tfoo"
        val foo = "Test Foo"
        val bar = 13
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )
        system.eventStream.subscribe( p.ref, classOf[Protocol.Event] )

        val aggregate = TestModule aggregateOf id
        logger.info( "----------  ADDIND ----------")
        aggregate !+ Protocol.Add( id, name, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated
        logger.info( "----------  DELETING ----------")

        whenReady( busRegister.futureGet( "Test Foo" ) ) { _ mustBe Some(id) }
        busRegister.get( "Test Foo" ) mustBe Some(id)

        aggregate !+ Protocol.Delete( id )

        bus.expectMsgPF( hint = "bus-deleted" ) {
          case payload: Protocol.Deleted => payload.sourceId mustBe id
        }

        p.expectMsgPF( hint = "stream-deleted" ) {
          case payload: Protocol.Deleted => payload.sourceId mustBe id
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 500.millis.dilated
        logger.info( "----------  CHECKING ----------")

        whenReady( busRegister futureGet "Test Foo" ) { actual =>
          logger info s"HERE ****: result(Test Foo) = $actual"
          actual mustBe None
        }

        whenReady( streamRegister futureGet 13 ) { actual =>
          logger info s"HERE ****: result(Damon) = $actual"
          actual mustBe None
        }

        whenReady( summaryRegister futureGet "tfoo" ) { actual =>
          logger info s"HERE ****: result(Damon) = $actual"
          actual mustBe None
        }
      }
    }

    "revised in index after change" in { fixture: Fixture =>
      import fixture._

      val rt = TestModule.rootType
      val br = model.aggregateIndexFor[String, TestModule.TID, TestModule.TID]( rt, 'bus )
      br.isRight mustBe true
      val sr = model.aggregateIndexFor[Int, TestModule.TID, TestModule.TID]( rt, 'stream )
      sr.isRight mustBe true
      val sumReg = model.aggregateIndexFor[String, TestModule.TID, Foo.Summary]( rt, 'summary )
      sumReg.isRight mustBe true
      for {
        busRegister <- br
        streamRegister <- sr
        summaryRegister <- sumReg
      } {
        val p = TestProbe()

        val id = TestModule.nextId.toOption.get
        val name = "tfoo"
        val foo = "Test Foo"
        val bar = 7
        system.eventStream.subscribe( bus.ref, classOf[Protocol.Event] )
        system.eventStream.subscribe( p.ref, classOf[Protocol.Event] )

        logger.info( "----------  ADDING ----------")
        val aggregate = TestModule aggregateOf id
        aggregate !+ Protocol.Add( id, name, foo, bar )

        bus.expectMsgPF( hint = "bus-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        p.expectMsgPF( hint = "stream-added" ) {
          case payload: Protocol.Added => {
            payload.sourceId mustBe id
            payload.foo mustBe foo
            payload.bar mustBe bar
          }
        }

        val countDownAdd = new CountDownFunction[String]
        countDownAdd await 200.millis.dilated

        whenReady( busRegister futureGet "Test Foo" ) { _ mustBe Some(id) }
        whenReady( streamRegister futureGet 7 ) { _ mustBe Some(id) }
        whenReady( summaryRegister futureGet "tfoo" ) {
          _ mustBe Some(Foo.Summary(id, Foo.Summary.foobar(foo, bar)))
        }

        busRegister.get( "Test Foo" ) mustBe Some(id)
        streamRegister.get( 7 ) mustBe Some(id)

        logger.info( "----------  CHANGING ----------")
        aggregate !+ Protocol.ChangeBar( id, 13 )

        bus.expectMsgPF( hint = "bar-change" ) {
          case payload: Protocol.BarChanged => {
            payload.oldBar mustBe 7
            payload.newBar mustBe 13
          }
        }

        p.expectMsgPF( hint = "post-bar change stream" ) {
          case payload: Protocol.BarChanged => {
            payload.oldBar mustBe 7
            payload.newBar mustBe 13
          }
        }

        val countDownChange = new CountDownFunction[String]
        countDownChange await 200.millis.dilated
        logger.info( "----------  CHECKING ----------")

        whenReady( busRegister.futureGet( "Test Foo" ) ) { result =>
          logger info s"HERE ****: result(Test Foo) = $result"
          result mustBe Some(id)
        }

        whenReady( streamRegister.futureGet( 7 ) ) { result =>
          logger info s"HERE ****: result(7) = $result"
          result mustBe None
        }

        whenReady( summaryRegister.futureGet( "tfoo" ) ) { result =>
          logger info s"HERE ****: result(13) = $result"
          result mustBe Some(Foo.Summary(id, Foo.Summary.foobar(foo, 13)))
        }
      }
    }
  }
}
