package ca.dvgi.periodic.ox

import ca.dvgi.periodic.*
import org.slf4j.Logger

import scala.concurrent.duration.{Duration, FiniteDuration}

class OxPeriodic extends Periodic[Identity, Identity] {
  def scheduleNow[T](
      log: Logger,
      operationName: String,
      fn: () => T,
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, T],
      blockUntilCompleteTimeout: Option[Duration] = None
  ): Unit = ???

  def scheduleRecurring[T](
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => T,
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit = ???

  def close(): Unit = ()
}
