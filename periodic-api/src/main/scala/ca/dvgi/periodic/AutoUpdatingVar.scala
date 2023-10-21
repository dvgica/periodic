package ca.dvgi.periodic

import scala.reflect.ClassTag
import org.slf4j.LoggerFactory

/** A variable that updates itself. `latest` can be called from multiple threads, which are all
  * guaranteed to get the latest var.
  *
  * An AutoUpdatingVar attempts to get the variable immediately upon class instantiation. If this
  * fails, there are no further attempts (unless specified via `handleInitializationError1), and the
  * effect returned by the `ready` method will complete unsuccesfully. If it succeeds, the effect
  * completes successfully and `latest` can be safely called.
  *
  * Failed updates other than the first (those that throw an exception) may be retried with various
  * configurations.
  *
  * A successful update schedules the next update, with an interval that can vary based on the
  * just-updated var.
  *
  * @param updateVar
  *   A thunk to initialize and update the var
  * @param updateInterval
  *   Configuration for the update interval
  * @param updateAttemptStrategy
  *   Configuration for retrying updates on failure
  * @param handleInitializationError
  *   A PartialFunction used to recover from exceptions in the var initialization. If unspecified,
  *   the exception will fail the effect returned by `ready`.
  * @param varNameOverride
  *   A name for this variable, used in logging. If unspecified, the simple class name of T will be
  *   used.
  */
class AutoUpdatingVar[U[_], R[_], T](
    autoUpdater: AutoUpdater[U, R, T],
    updateVar: => U[T],
    updateInterval: UpdateInterval[T],
    updateAttemptStrategy: UpdateAttemptStrategy,
    handleInitializationError: PartialFunction[Throwable, U[T]] = PartialFunction.empty,
    varNameOverride: Option[String] = None
)(implicit ct: ClassTag[T])
    extends AutoCloseable {

  private val varName = varNameOverride match {
    case Some(n) => n
    case None    => ct.runtimeClass.getSimpleName
  }

  private val log = LoggerFactory.getLogger(s"AutoUpdatingVar[$varName]")

  log.info(s"$this: Starting. ${updateAttemptStrategy.description}")

  /** @return
    *   An effect which, once successfully completed, signifies that the AutoUpdatingVar has a
    *   value, i.e. `latest` can be called and no exception will be thrown.
    */
  def ready: R[Unit] = autoUpdater.start(
    log,
    updateVar,
    updateInterval,
    updateAttemptStrategy,
    handleInitializationError
  )

  /** Wait for `ready` to be completed before calling this method.
    *
    * @return
    *   The latest value of the variable. Calling this method is thread-safe.
    * @throws UnreadyAutoUpdatingVarException
    *   if there is not yet a value to return
    */
  def latest: T = autoUpdater.latest

  override def close(): Unit = {
    autoUpdater.close()
    log.info(s"$this: Shutting down")
  }
}
