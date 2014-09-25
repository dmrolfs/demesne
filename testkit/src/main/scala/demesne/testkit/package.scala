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
    """.stripMargin
  )

  def system: ActorSystem = system( config )

  def system( config: Config ): ActorSystem = ActorSystem( "Tests", config )
}
