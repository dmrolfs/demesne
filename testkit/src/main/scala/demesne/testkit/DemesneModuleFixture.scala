package demesne.testkit

import akka.testkit.TestKit
import demesne.{AggregateRootModule, DomainModel}
import org.scalatest.{Suite, BeforeAndAfterEach}


/**
 * Created by damonrolfs on 9/25/14.
 */
trait DemesneModuleFixture extends BeforeAndAfterEach { outer: Suite with TestKit =>
  def module: AggregateRootModule

  def model: DomainModel = DomainModel()

  def context: Map[Symbol, Any] = {
    Map(
      demesne.ModelKey -> model,
      demesne.SystemKey -> system,
      demesne.FactoryKey -> demesne.factory.systemFactory
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    module.start( context )
  }

  override def afterEach(): Unit = {
    TestKit.shutdownActorSystem( system )
    system.awaitTermination()
    super.afterEach()
  }
}
