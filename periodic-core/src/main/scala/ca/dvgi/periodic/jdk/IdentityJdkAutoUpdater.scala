package ca.dvgi.periodic.jdk

import scala.concurrent.duration.Duration
import java.util.concurrent.ScheduledExecutorService

class IdentityJdkAutoUpdater[T](
    blockUntilReadyTimeout: Option[Duration] = None,
    executorOverride: Option[ScheduledExecutorService] = None
) extends JdkAutoUpdater[Identity, T](blockUntilReadyTimeout, executorOverride) {
  override protected def evalUpdate(ut: Identity[T]): T = ut
}

object IdentityJdkAutoUpdater {
  def apply[T](
      blockUntilReadyTimeout: Option[Duration] = None,
      executorOverride: Option[ScheduledExecutorService] = None
  ): IdentityJdkAutoUpdater[T] =
    new IdentityJdkAutoUpdater(blockUntilReadyTimeout, executorOverride)
}
