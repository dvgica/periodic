package ca.dvgi.periodic.jdk

import scala.concurrent.duration.Duration
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.Future
import scala.concurrent.Await

class FutureJdkAutoUpdater[T](
    blockUntilReadyTimeout: Option[Duration] = None,
    executorOverride: Option[ScheduledExecutorService] = None
) extends JdkAutoUpdater[Future, T](blockUntilReadyTimeout, executorOverride) {
  override protected def evalUpdate[A](ua: Future[A]): A = Await.result(ua, Duration.Inf)

  override protected def pureUpdate[A](a: A): Future[A] = Future.successful(a)
}

object FutureJdkAutoUpdater {
  def apply[T](
      blockUntilReadyTimeout: Option[Duration] = None,
      executorOverride: Option[ScheduledExecutorService] = None
  ): FutureJdkAutoUpdater[T] =
    new FutureJdkAutoUpdater(blockUntilReadyTimeout, executorOverride)
}
