package ca.dvgi.periodic

import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import scala.reflect.ClassTag

object AutoUpdatingVar {
  sealed trait UpdateAttemptExhaustionBehavior {
    def run: String => Unit
  }
  object UpdateAttemptExhaustionBehavior {
    case class Terminate(exitCode: Int = 1) extends UpdateAttemptExhaustionBehavior {
      private val log = LoggerFactory.getLogger(getClass)

      def run: String => Unit = name => {
        log.error(s"$name: Var update attempts exhausted, will now attempt to exit the process...")
        sys.exit(exitCode)
      }
    }
    case class Custom(run: String => Unit) extends UpdateAttemptExhaustionBehavior
  }

  sealed trait UpdateAttemptStrategy {
    def attemptInterval: FiniteDuration
  }
  object UpdateAttemptStrategy {
    case class Infinite(attemptInterval: FiniteDuration) extends UpdateAttemptStrategy
    case class Finite(
        attemptInterval: FiniteDuration,
        maxAttempts: Int,
        attemptExhaustionBehavior: UpdateAttemptExhaustionBehavior =
          UpdateAttemptExhaustionBehavior.Terminate()
    ) extends UpdateAttemptStrategy
  }

  sealed trait UpdateInterval[-T]
  object UpdateInterval {
    case class Fixed(interval: FiniteDuration) extends UpdateInterval[Any]
    case class Dynamic[T](calculateUpdateInterval: T => FiniteDuration) extends UpdateInterval[T]
  }
}

/** A variable that updates itself. `latest` can be called from multiple threads, which are all
  * guaranteed to get the latest var.
  *
  * The initial update of the variable will block the thread which called `new AutoUpdatingVar`.
  * This is to ensure that `latest` will always return a value (not an Option or Future value).
  *
  * Failed updates (those that throw an exception) may be retried with various configurations.
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
  * @param varNameOverride
  *   A name for this variable, used in logging. If unspecified, the simple class name of T will be
  *   used.
  * @param handleInitializationError
  *   A PartialFunction used to recover from exceptions in the var initialization. If unspecified,
  *   the exception will be thrown in the thread which called `new AutoUpdatingVar`.
  */
class AutoUpdatingVar[T](
    updateVar: => T,
    updateInterval: AutoUpdatingVar.UpdateInterval[T],
    updateAttemptStrategy: AutoUpdatingVar.UpdateAttemptStrategy,
    varNameOverride: Option[String] = None,
    handleInitializationError: PartialFunction[Throwable, T] = PartialFunction.empty
)(implicit ct: ClassTag[T])
    extends AutoCloseable {
  import AutoUpdatingVar._

  private val log = LoggerFactory.getLogger(getClass)

  private val executor = Executors.newScheduledThreadPool(1)

  private val varName = varNameOverride match {
    case Some(n) => n
    case None    => ct.runtimeClass.getSimpleName
  }

  /** @return
    *   The latest value of the variable. Calling this method is thread-safe.
    */
  final def latest: T = variable

  override def close(): Unit = {
    log.info(s"$this: Shutting down")
    executor.shutdownNow()
    ()
  }

  override def toString: String = s"AutoUpdatingVar($varName)"

  private val attemptsLog = updateAttemptStrategy match {
    case UpdateAttemptStrategy.Finite(interval, maxAttempts, _) =>
      s"When updating var, will attempt a maximum of $maxAttempts times every $interval."
    case UpdateAttemptStrategy.Infinite(interval) =>
      s"When updating var, will reattempt indefinitely every $interval."
  }

  log.info(s"$this: Starting. $attemptsLog")

  @volatile private var variable: T =
    try {
      try {
        val v = updateVar
        log.info(s"$this: Successfully initialized")
        v
      } catch {
        case NonFatal(e) =>
          log.error(s"$this: Failed to initialize var", e)
          throw e
      }
    } catch (handleInitializationError)

  scheduleUpdate(calculateUpdateInterval(variable))

  private def calculateUpdateInterval(variable: T): FiniteDuration = updateInterval match {
    case UpdateInterval.Fixed(interval)                  => interval
    case UpdateInterval.Dynamic(calculateUpdateInterval) => calculateUpdateInterval(variable)
  }

  private def scheduleUpdate(nextUpdate: FiniteDuration): Unit = {
    log.info(s"$this: Scheduling update of var in: $nextUpdate")

    executor.schedule(new UpdateVar(1), nextUpdate.length, nextUpdate.unit)
    ()
  }

  private class UpdateVar(attempt: Int) extends Runnable {
    def run(): Unit = {
      try {
        variable = updateVar
        log.info(s"$this: Successfully updated")
        scheduleUpdate(calculateUpdateInterval(variable))
      } catch {
        case NonFatal(e) =>
          updateAttemptStrategy match {
            case UpdateAttemptStrategy.Infinite(attemptInterval) =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(attemptInterval, maxAttempts, _)
                if attempt < maxAttempts =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(_, _, attemptExhaustionBehavior) =>
              log.error(s"$this: Var update attempts exhausted! Final attempt exception", e)
              attemptExhaustionBehavior.run(varName)
          }
      }
    }

    private def reattempt(e: Throwable, delay: FiniteDuration): Unit = {
      log.warn(
        s"$this: Unhandled exception when trying to update var, retrying in $delay",
        e
      )
      executor.schedule(
        new UpdateVar(attempt + 1),
        delay.length,
        delay.unit
      )
      ()
    }
  }
}
