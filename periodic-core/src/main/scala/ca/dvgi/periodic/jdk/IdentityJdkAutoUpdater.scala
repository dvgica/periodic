package ca.dvgi.periodic.jdk

import scala.concurrent.duration.Duration
import java.util.concurrent.ScheduledExecutorService

class IdentityJdkAutoUpdater[T](
    blockUntilReadyTimeout: Option[Duration] = None,
    executorOverride: Option[ScheduledExecutorService] = None
) extends JdkAutoUpdater[Identity, T](blockUntilReadyTimeout, executorOverride) {
  override protected def evalUpdate[A](ua: Identity[A]): A = ua

  override protected def pureUpdate[A](a: A): Identity[A] = a
}

object IdentityJdkAutoUpdater {
  def apply[T](
      blockUntilReadyTimeout: Option[Duration] = None,
      executorOverride: Option[ScheduledExecutorService] = None
  ): IdentityJdkAutoUpdater[T] =
    new IdentityJdkAutoUpdater(blockUntilReadyTimeout, executorOverride)
}
