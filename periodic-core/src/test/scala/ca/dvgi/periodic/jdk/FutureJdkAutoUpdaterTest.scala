package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Await
import scala.concurrent.Future

class FutureJdkAutoUpdaterTest extends AutoUpdaterTestsFuture[Future] {
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  def evalU[T](ut: Future[T]): T = Await.result(ut, Duration.Inf)

  def pureU(thunk: => Int): Future[Int] = Future(thunk)

  def autoUpdaterBuilder() = new FutureJdkAutoUpdater[Int](_, None)

  testAll(autoUpdaterBuilder())

  FunFixture(
    _ => {
      val holder = new VarHolder
      val ses = Executors.newScheduledThreadPool(1)
      val v =
        new AutoUpdatingVar(
          new FutureJdkAutoUpdater[Int](Some(1.second), executorOverride = Some(ses))
        )(
          holder.get,
          UpdateInterval.Static(2.seconds),
          UpdateAttemptStrategy.Infinite(1.second)
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
