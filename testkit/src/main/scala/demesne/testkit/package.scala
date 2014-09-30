package demesne

import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}


/**
 * Created by damonrolfs on 9/18/14.
 */
package object testkit {
  val config: Config = ConfigFactory.parseString(
    """
      |akka.loggers = ["akka.testkit.TestEventListener"]
      |akka.actor.debug.autoreceive = "on"
      |ecommerce.broker.url = "nio://0.0.0.0:61616"
      |ecommerce.view.db.url = "jdbc:h2:tcp://localhost:8092/~/ecommerce"
      |ecommerce.view.db.driver = "org.h2.Driver"
      |akka.persistence.journal.plugin = "in-memory-journal"
      |akka.loglevel = DEBUG
      |akka.actor.debug {
      |      # enable function of Actor.loggable(), which is to log any received message
      |      # at DEBUG level, see the “Testing Actor Systems” section of the Akka
      |      # Documentation at http://akka.io/docs
      |      receive = on
      |
      |      # enable DEBUG logging of all AutoReceiveMessages (Kill, PoisonPill et.c.)
      |      autoreceive = on
      |
      |      # enable DEBUG logging of actor lifecycle changes
      |      lifecycle = on
      |
      |      # enable DEBUG logging of all LoggingFSMs for events, transitions and timers
      |      fsm = on
      |
      |      # enable DEBUG logging of subscription changes on the eventStream
      |      event-stream = on
      |
      |      # enable DEBUG logging of unhandled messages
      |      unhandled = on
      |
      |      # enable WARN logging of misconfigured routers
      |      router-misconfiguration = on
      |}
    """.stripMargin
  )

  def system: ActorSystem = system( config )

  def system( config: Config ): ActorSystem = ActorSystem( "Tests", config )
}
