package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Await
import scala.concurrent.Future

class FutureJdkAutoUpdatingVarTest extends AutoUpdatingVarTestsFuture[Future] {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  def evalU[T](ut: Future[T]): T = Await.result(ut, Duration.Inf)

  def pureU(thunk: => Int): Future[Int] = Future(thunk)

  def periodicBuilder() = new JdkPeriodic[Future]()

  testAll(periodicBuilder)

  FunFixture(
    _ => {
      val holder = new VarHolder
      val ses = Executors.newScheduledThreadPool(1)
      val v =
        new AutoUpdatingVar(
          JdkPeriodic[Future](Some(ses))
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
    assertEquals(evalU(holder.get), 2)
  }
}
