package ca.dvgi.periodic

import scala.reflect.ClassTag
import org.slf4j.LoggerFactory
import scala.annotation.nowarn

/** A variable that updates itself. `latest` can be called from multiple threads, which are all
  * guaranteed to get the latest var.
  *
  * An AutoUpdatingVar attempts to get the variable immediately upon class instantiation. If this
  * fails, there are no further attempts, and the effect returned by the `ready` method will
  * complete unsuccesfully. If it succeeds, the effect completes successfully and `latest` can be
  * safely called.
  *
  * Failed updates (those that throw an exception) may be retried with various configurations.
  * However, if the initial update of the var during class instantiationfails
  *
  * A successful update schedules the next update, with an interval that can vary based on the
  * just-updated var.
  *
  * @param updateVar
  *   A thunk run to initialize and update the var
  * @param updateInterval
  *   Configuration for the update interval
  * @param updateAttemptStrategy
  *   Configuration for attempting updates
  * @param handleInitializationError
  *   A PartialFunction used to recover from exceptions in the var initialization. If unspecified,
  *   the exception will fail the effect returned by `ready`.
  * @param varNameOverride
  *   A name for this variable, used in logging. If unspecified, the simple class name of T will be
  *   used.
  */
abstract class AutoUpdatingVar[U[_], R[_], T](
    @nowarn updateVar: => U[T],
    @nowarn updateInterval: UpdateInterval[T],
    updateAttemptStrategy: UpdateAttemptStrategy,
    @nowarn handleInitializationError: PartialFunction[Throwable, U[T]] = PartialFunction.empty,
    varNameOverride: Option[String] = None
)(implicit ct: ClassTag[T])
    extends AutoCloseable {

  /** @return
    *   An effect which, once successfully completed, signifies that the AutoUpdatingVar has a
    *   value, i.e. `latest` can be called and no exception will be thrown.
    */
  def ready: R[Unit]

  /** Wait for `ready` to be completed before calling this method.
    *
    * @return
    *   The latest value of the variable. Calling this method is thread-safe.
    * @throws UnreadyAutoUpdatingVarException
    *   if there is not yet a value to return
    */
  def latest: T

  override def close(): Unit = {
    log.info(s"$this: Shutting down")
  }

  private val log = LoggerFactory.getLogger(getClass)

  log.info(s"$this: Starting. ${updateAttemptStrategy.description}")

  override def toString: String = s"AutoUpdatingVar($varName)"

  protected val varName = varNameOverride match {
    case Some(n) => n
    case None    => ct.runtimeClass.getSimpleName
  }

  protected def logString(msg: String): String = s"$this: $msg"
}
