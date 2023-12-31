package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration
import org.slf4j.Logger
import scala.concurrent.duration.Duration

trait Periodic[F[_], R[_]] extends AutoCloseable {
  def scheduleNow[T](
      log: Logger,
      operationName: String,
      fn: () => F[T],
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, F[T]],
      blockUntilCompleteTimeout: Option[Duration] = None
  ): R[Unit]

  def scheduleRecurring[T](
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => F[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit
}
