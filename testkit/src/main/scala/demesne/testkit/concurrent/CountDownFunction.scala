package demesne.testkit.concurrent

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.Duration


class CountDownFunction[A]( num: Int = 1 ) extends Function1[A, A] {
  val latch = new CountDownLatch( num )
  def apply( a: A ) = { latch.countDown(); a }
  def await( timeout: Duration ) = latch.await( timeout.length, timeout.unit )
}
