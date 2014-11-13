package demesne

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}


/**
 * Created by damonrolfs on 9/18/14.
 */
package object testkit {
  val config: Config = ConfigFactory.parseString(
    """
      |#akka.loggers = ["akka.testkit.TestEventListener"]
      |
      |akka.persistence.journal.plugin = "in-memory-journal"
      |#akka.persistence {
      |#  journal.plugin = "akka.persistence.journal.leveldb-shared"
      |#  journal.leveldb-shared.store {
      |#    # DO NOT USE 'native = off' IN PRODUCTION !!!
      |#    native = off
      |#    dir = "target/shared-journal"
      |#  }
      |#  snapshot-store.local.dir = "target/snapshots"
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
      |demesne.register-dispatcher {
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

  def system: ActorSystem = system( config )

  def system( config: Config ): ActorSystem = ActorSystem( "Tests", config )
}
