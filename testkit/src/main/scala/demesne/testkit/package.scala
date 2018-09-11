package demesne

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }

/**
  * Created by damonrolfs on 9/18/14.
  */
package object testkit {

  val config: Config = ConfigFactory.parseString(
    """
      |include "kryo"
      |#akka.actor.kryo.idstrategy = automatic
      |
      |#akka.loggers = ["akka.testkit.TestEventListener"]
      |
      |#akka.persistence.journal.plugin = "in-memory-journal"
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
      |  persistence {
      |    journal.plugin = "inmemory-journal"
      |    snapshot-store.plugin = "inmemory-snapshot-store"
      |  }
      |}
      |
      |akka {
      |
      |#  loggers = ["akka.event.slf4j.Slf4jLogger"]
      |#  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
      |
      |#  log-config-on-start = off
      |#  log-dead-letters-during-shutdown = off
      |  # Options: off, error, warning, info, debug
      |  loglevel = debug
      |  stdout-loglevel = debug
      |
      |  actor.debug {
      |    #    receive = off
      |    #    lifecycle = off
      |    #    autoreceive = off
      |    unhandled = on
      |    router-misconfiguration = on
      |  }
      |
      |
      |  loggers = ["akka.testkit.TestEventListener"]  # "akka.event.slf4j.Slf4jLogger",
      |  logging-filter = "akka.event.DefaultLoggingFilter"
      |
      |  log-dead-letters = on
      |  log-dead-letters-during-shutdown = on
      |
      |  actor.provider = "cluster"
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
      |akka.cluster.distributed-data.durable.lmdb.dir = "./target/ddata"
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
      |          key = "8fjF%F4d$w!ZPUfu"
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

  def system: ActorSystem = system( config )

  def system( config: Config ): ActorSystem = ActorSystem( "Tests", config )
}
