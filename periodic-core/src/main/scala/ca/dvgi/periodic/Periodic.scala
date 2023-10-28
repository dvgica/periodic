package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration
import org.slf4j.Logger

trait Periodic[F[_], T] extends AutoCloseable {
  def start(
      log: Logger,
      initialDelay: FiniteDuration,
      fn: () => F[T],
      interval: F[T] => F[FiniteDuration],
      attemptStrategy: UpdateAttemptStrategy
  ): Unit
}
