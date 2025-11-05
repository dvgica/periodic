package ca.dvgi.periodic.ox

import ca.dvgi.periodic.*
import org.slf4j.Logger
import _root_.ox.*

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.util.control.NonFatal
import _root_.ox.scheduling.*
import _root_.ox.resilience.retry
import _root_.ox.resilience.RetryConfig

class OxPeriodic(implicit ox: Ox) extends Periodic[Identity, Fork] {
  def scheduleNow[T](
      log: Logger,
      operationName: String,
      fn: () => T,
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, T],
      blockUntilCompleteTimeout: Option[FiniteDuration] = None
  ): Fork[Unit] = {
    def doFn(): Unit = {
      log.info(s"Attempting to $operationName...")

      Try(fn()) match {
        case Success(v) =>
          onSuccess(v)
          log.info(s"Successfully completed $operationName")
          ()

        case Failure(NonFatal(e)) =>
          log.warn(s"Failed to $operationName", e)
          handleError.applyOrElse(e, (t: Throwable) => throw t): Unit

        case Failure(e) => throw e
      }
    }

    blockUntilCompleteTimeout match {
      case Some(t) =>
        val _ = timeout(t)(doFn())
        Fork.successful(())
      case None =>
        fork {
          doFn()
        }
    }
  }

  def scheduleRecurring[T](
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => T,
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit = {
    scheduleNext(initialDelay)(log, operationName, fn, onSuccess, interval, attemptStrategy)
  }

  private def scheduleNext[T](delay: FiniteDuration)(implicit
      log: Logger,
      operationName: String,
      fn: () => T,
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit = {
    log.info(s"Scheduling next $operationName in: $delay")

    val intervalSchedule =
      Schedule.fixedInterval(attemptStrategy.attemptInterval).withInitialDelay(delay)

    val schedule = attemptStrategy match {
      case AttemptStrategy.Finite(_, maxAttempts, _) =>
        intervalSchedule.maxAttempts(maxAttempts)
      case AttemptStrategy.Infinite(_) =>
        intervalSchedule
    }

    def runFn(): T = {
      log.info(s"Attempting $operationName...")
      fn()
    }

    def afterAttempt(attemptNum: Int, result: Either[Throwable, T]): Unit = {
      attemptStrategy match {
        case AttemptStrategy.Finite(_, max, _) if attemptNum >= max =>
          () // no-op
        case _ =>
          result.left.foreach(e =>
            log.warn(
              s"Unhandled exception during $operationName, retrying in ${attemptStrategy.attemptInterval}",
              e
            )
          )
      }
    }

    val retryConfig = RetryConfig(schedule, afterAttempt = afterAttempt)

    Try(retry(retryConfig)(runFn())) match {
      case Success(r) =>
        log.info(s"Successfully executed $operationName")
        onSuccess(r) // TODO handle errors in this
        scheduleNext(interval(r))
      case Failure(e) =>
        log.error(
          s"${operationName.capitalize} retries exhausted! Final attempt exception",
          e
        )
        attemptStrategy match {
          case s: AttemptStrategy.Finite => s.attemptExhaustionBehavior.run(log)
          case _                         =>
            // should never happen
            log.error(
              "Somehow exhausted infinite attempts! Something is very wrong. Attempting to exit..."
            )
            AttemptExhaustionBehavior.Terminate().run(log)
        }
    }
  }

  def close(): Unit = ()
}
