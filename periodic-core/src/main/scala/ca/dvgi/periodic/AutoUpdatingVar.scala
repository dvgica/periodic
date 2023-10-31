package ca.dvgi.periodic

import scala.reflect.ClassTag
import org.slf4j.LoggerFactory
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import ca.dvgi.periodic.jdk._
import java.util.concurrent.ScheduledExecutorService

/** A variable that updates itself. `latest` can be called from multiple threads, which are all
  * guaranteed to get the latest var.
  *
  * An AutoUpdatingVar attempts to get the variable immediately upon class instantiation. If this
  * fails, there are no further attempts (unless specified via `handleInitializationError`), and the
  * effect returned by the `ready` method will complete unsuccesfully. If it succeeds, the effect
  * completes successfully and `latest` can be safely called.
  *
  * Failed updates other than the first (those that throw an exception) may be retried with various
  * configurations.
  *
  * A successful update schedules the next update, with an interval that can vary based on the
  * just-updated var.
  *
  * @param periodic
  *   A Periodic instance used to update the var
  * @param updateVar
  *   A thunk to initialize and update the var
  * @param updateInterval
  *   Configuration for the update interval
  * @param updateAttemptStrategy
  *   Configuration for retrying updates on failure
  * @param blockUntilReadyTimeout
  *   If specified, will cause the AutoUpdatingVar constructor to block until an initial value is
  *   computed, or there is a timeout or failure. This means that the effect returned by `ready`
  *   will always be complete.
  * @param handleInitializationError
  *   A PartialFunction used to recover from exceptions in the var initialization. If unspecified,
  *   the exception will fail the effect returned by `ready`.
  * @param varNameOverride
  *   A name for this variable, used in logging. If unspecified, the simple class name of T will be
  *   used.
  */
class AutoUpdatingVar[U[_], R[_], T](periodic: Periodic[U, R, T])(
    updateVar: => U[T],
    updateInterval: UpdateInterval[T],
    updateAttemptStrategy: AttemptStrategy,
    blockUntilReadyTimeout: Option[Duration] = None,
    handleInitializationError: PartialFunction[Throwable, U[T]] = PartialFunction.empty,
    varNameOverride: Option[String] = None
)(implicit ct: ClassTag[T])
    extends AutoCloseable {

  private val varName = varNameOverride match {
    case Some(n) => n
    case None    => ct.runtimeClass.getSimpleName
  }

  private val log = LoggerFactory.getLogger(s"AutoUpdatingVar[$varName]")

  log.info(s"Starting. ${updateAttemptStrategy.description}")

  @volatile private var variable: Option[T] = None

  private val _ready = periodic.scheduleNow(
    log,
    "initialize var",
    () => updateVar,
    newV => {
      variable = Some(newV)
      periodic.scheduleRecurring(
        log,
        "update var",
        updateInterval.duration(newV),
        () => updateVar,
        v => variable = Some(v),
        v => updateInterval.duration(v),
        updateAttemptStrategy
      )
    },
    handleInitializationError,
    blockUntilReadyTimeout
  )

  /** @return
    *   An effect which, once successfully completed, signifies that the AutoUpdatingVar has a
    *   value, i.e. `latest` can be called and no exception will be thrown.
    */
  def ready: R[Unit] = _ready

  /** Get the latest variable value from memory. Does not attempt to update the var.
    *
    * Wait for `ready` to be completed before calling this method.
    *
    * @return
    *   The latest value of the variable. Calling this method is thread-safe.
    * @throws UnreadyAutoUpdatingVarException
    *   if there is not yet a value to return
    */
  def latest: T = variable.getOrElse(throw UnreadyAutoUpdatingVarException)

  override def close(): Unit = {
    periodic.close()
    log.info(s"Shut down sucessfully")
  }
}

object AutoUpdatingVar {

  /** @see
    *   [[ca.dvgi.periodic.AutoUpdatingVar]]
    */
  def apply[U[_], R[_], T](periodic: Periodic[U, R, T])(
      updateVar: => U[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: AttemptStrategy,
      blockUntilReadyTimeout: Option[Duration] = None,
      handleInitializationError: PartialFunction[Throwable, U[T]] = PartialFunction.empty,
      varNameOverride: Option[String] = None
  )(implicit ct: ClassTag[T]): AutoUpdatingVar[U, R, T] = {
    new AutoUpdatingVar(periodic)(
      updateVar,
      updateInterval,
      updateAttemptStrategy,
      blockUntilReadyTimeout,
      handleInitializationError,
      varNameOverride
    )
  }

  /** An AutoUpdatingVar based on only the JDK.
    *
    * @see
    *   [[ca.dvgi.periodic.jdk.JdkPeriodic]]
    * @see
    *   [[ca.dvgi.periodic.AutoUpdatingVar]]
    */
  def jdk[T](
      updateVar: => T,
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: AttemptStrategy,
      blockUntilReadyTimeout: Option[Duration] = None,
      handleInitializationError: PartialFunction[Throwable, T] = PartialFunction.empty,
      varNameOverride: Option[String] = None,
      executorOverride: Option[ScheduledExecutorService] = None
  )(implicit ct: ClassTag[T]): AutoUpdatingVar[Identity, Future, T] = {
    new AutoUpdatingVar(
      new JdkPeriodic[Identity, T](executorOverride)
    )(
      updateVar,
      updateInterval,
      updateAttemptStrategy,
      blockUntilReadyTimeout,
      handleInitializationError,
      varNameOverride
    )
  }

  /** An AutoUpdatingVar based on only the JDK, for use when `updateVar` returns a `Future`.
    *
    * @see
    *   [[ca.dvgi.periodic.jdk.JdkPeriodic]]
    * @see
    *   [[ca.dvgi.periodic.AutoUpdatingVar]]
    */
  def jdkFuture[T](
      updateVar: => Future[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: AttemptStrategy,
      blockUntilReadyTimeout: Option[Duration] = None,
      handleInitializationError: PartialFunction[Throwable, Future[T]] = PartialFunction.empty,
      varNameOverride: Option[String] = None,
      executorOverride: Option[ScheduledExecutorService] = None
  )(implicit ct: ClassTag[T]): AutoUpdatingVar[Future, Future, T] = {
    new AutoUpdatingVar(
      new JdkPeriodic[Future, T](executorOverride)
    )(
      updateVar,
      updateInterval,
      updateAttemptStrategy,
      blockUntilReadyTimeout,
      handleInitializationError,
      varNameOverride
    )
  }
}
