package ca.dvgi.periodic

import org.slf4j.Logger

trait AutoUpdater[U[_], R[_], T] extends AutoCloseable {
  def start(
      log: Logger,
      updateVar: => U[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy,
      handleInitializationError: PartialFunction[Throwable, U[T]] = PartialFunction.empty
  ): R[Unit]

  def latest: T
}
