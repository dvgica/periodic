package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration
import org.slf4j.Logger
import scala.concurrent.duration.Duration

trait Periodic[F[_], R[_], T] extends AutoCloseable {
  def scheduleNow(
      log: Logger,
      operationName: String,
      fn: () => F[T],
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, F[T]],
      blockUntilCompleteTimeout: Option[Duration] = None
  ): R[Unit]

  def scheduleRecurring(
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => F[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: UpdateAttemptStrategy
  ): Unit
}
