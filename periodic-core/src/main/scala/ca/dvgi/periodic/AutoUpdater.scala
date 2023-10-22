package ca.dvgi.periodic

import org.slf4j.Logger

/** AutoUpdatingVar delegates most functionality to an AutoUpdater, which may have many
  * implementations.
  */
trait AutoUpdater[U[_], R[_], T] extends AutoCloseable {

  /** Initializes the var for the first time, handling errors as specified. If successful, schedules
    * the next update.
    *
    * @param log
    *   Implementations should use this logger for consistency.
    *
    * @return
    *   An effect, which, if successfully completed, signifies that a value is available. If
    *   initialization failed, the effect should also be failed.
    */
  def start(
      log: Logger,
      updateVar: () => U[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy,
      handleInitializationError: PartialFunction[Throwable, U[T]]
  ): R[Unit]

  /** The latest in-memory value of the variable.
    *
    * @return
    *   Some[T] if the variable has been initialized successfully, otherwise None.
    */
  def latest: Option[T]
}
