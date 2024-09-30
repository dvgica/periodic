package ca.dvgi.periodic

import ca.dvgi.periodic.jdk._
import org.slf4j.LoggerFactory
import scala.concurrent.duration._
import scala.concurrent.Future

/** A FnRunner executes a side-effecting function periodically. The `FiniteDuration` returned from
  * the function determines the delay before the next run.
  *
  * Failed runs may be retried with various configurations.
  *
  * @param periodic
  *   A Periodic instance used to run the function
  * @param fn
  *   The side-effecting function to run periodically
  * @param fnAttemptStrategy
  *   Configuration for retrying runs on failure
  * @param fnName
  *   A human-friendly description of the function, used in logging
  * @param initialDelay
  *   If specified, the first run of the function will be delayed this much
  */
class FnRunner[F[_], R[_]](periodic: Periodic[F, R])(
    fn: => F[FiniteDuration],
    fnAttemptStrategy: AttemptStrategy,
    fnName: String,
    initialDelay: FiniteDuration = 0.seconds
) extends AutoCloseable {

  private val log = LoggerFactory.getLogger(s"FnRunner[$fnName]")

  log.info(s"Starting. ${fnAttemptStrategy.description}")

  periodic.scheduleRecurring[FiniteDuration](
    log,
    fnName,
    initialDelay,
    () => fn,
    _ => (),
    identity,
    fnAttemptStrategy
  )

  override def close(): Unit = {
    periodic.close()
    log.info(s"Shut down sucessfully")
  }
}

object FnRunner {
  def apply[F[_], R[_]](periodic: Periodic[F, R])(
      fn: => F[FiniteDuration],
      fnAttemptStrategy: AttemptStrategy,
      fnName: String,
      initialDelay: FiniteDuration = 0.seconds
  ): FnRunner[F, R] = new FnRunner(periodic)(fn, fnAttemptStrategy, fnName, initialDelay)

  def jdk[F[_]](
      fn: => FiniteDuration,
      fnAttemptStrategy: AttemptStrategy,
      fnName: String,
      initialDelay: FiniteDuration = 0.seconds
  ): FnRunner[Identity, Future] =
    new FnRunner(JdkPeriodic[Identity]())(
      fn,
      fnAttemptStrategy,
      fnName,
      initialDelay
    )

  def jdkFuture[F[_]](
      fn: => Future[FiniteDuration],
      fnAttemptStrategy: AttemptStrategy,
      fnName: String,
      initialDelay: FiniteDuration = 0.seconds
  ): FnRunner[Future, Future] =
    new FnRunner(JdkPeriodic[Future]())(
      fn,
      fnAttemptStrategy,
      fnName,
      initialDelay
    )
}
