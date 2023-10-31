package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class IdentityJdkAutoUpdatingVarTest extends AutoUpdatingVarTestsFuture[Identity] {

  def evalU[T](ut: Identity[T]): T = ut

  def pureU(thunk: => Int): Identity[Int] = thunk

  def periodicBuilder() = new JdkPeriodic[Identity]

  testAll(periodicBuilder)

  FunFixture(
    _ => {
      val holder = new VarHolder
      val ses = Executors.newScheduledThreadPool(1)
      val v =
        AutoUpdatingVar(
          JdkPeriodic[Identity](Some(ses))
        )(
          holder.get,
          UpdateInterval.Static(2.seconds),
          AttemptStrategy.Infinite(1.second),
          Some(1.second)
        )
      (v, holder, ses)
    },
    (f: (AutoCloseable, VarHolder, ScheduledExecutorService)) => {
      f._1.close()
      f._3.shutdownNow()
      ()
    }
  ).test("can use an external SchedulerExecutorService") { case (v, holder, ses) =>
    assertEquals(v.latest, 1)

    v.close()
    assert(!ses.isShutdown())

    Thread.sleep(5000)
    assertEquals(holder.get, 2)
  }
}
