package ca.dvgi.periodic.jdk

import scala.concurrent.duration.FiniteDuration
import ca.dvgi.periodic.UpdateAttemptStrategy
import ca.dvgi.periodic.Periodic
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import scala.util.control.NonFatal
import org.slf4j.Logger

class JdkPeriodic[F[_], T](
    operationName: String,
    evalF: F[FiniteDuration] => FiniteDuration,
    executor: ScheduledExecutorService
) extends Periodic[F, T] {

  private case object CloseLock

  @volatile private var closed = false

  @volatile private var nextTask: Option[ScheduledFuture[_]] = None

  override def start(
      log: Logger,
      initialDelay: FiniteDuration,
      fn: () => F[T],
      interval: F[T] => F[FiniteDuration],
      attemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    scheduleUpdate(initialDelay)(log, fn, interval, attemptStrategy)
  }

  private def scheduleUpdate(nextUpdate: FiniteDuration)(implicit
      log: Logger,
      fn: () => F[T],
      interval: F[T] => F[FiniteDuration],
      attemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    CloseLock.synchronized {
      if (!closed)
        nextTask = Some(
          executor.schedule(
            new FnRunnable(1),
            nextUpdate.length,
            nextUpdate.unit
          )
        )
    }
    ()
  }

  private class FnRunnable(attempt: Int)(implicit
      log: Logger,
      fn: () => F[T],
      interval: F[T] => F[FiniteDuration],
      attemptStrategy: UpdateAttemptStrategy
  ) extends Runnable {
    def run(): Unit = {
      try {
        log.info(s"Attempting $operationName...")
        val result = fn()
        log.info(s"Successfully executed $operationName")
        scheduleUpdate(evalF(interval(result)))
      } catch {
        case NonFatal(e) =>
          attemptStrategy match {
            case UpdateAttemptStrategy.Infinite(attemptInterval) =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(attemptInterval, maxAttempts, _)
                if attempt < maxAttempts =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(_, _, attemptExhaustionBehavior) =>
              log.error(
                s"${operationName.capitalize} attempts exhausted! Final attempt exception",
                e
              )
              attemptExhaustionBehavior.run(log)
          }
      }
    }

    private def reattempt(e: Throwable, delay: FiniteDuration)(implicit
        log: Logger,
        fn: () => F[T],
        interval: F[T] => F[FiniteDuration],
        attemptStrategy: UpdateAttemptStrategy
    ): Unit = {
      log.warn(
        s"Unhandled exception when trying to $operationName, retrying in $delay",
        e
      )

      CloseLock.synchronized {
        if (!closed)
          nextTask = Some(
            executor.schedule(
              new FnRunnable(attempt + 1),
              delay.length,
              delay.unit
            )
          )
      }
      ()
    }
  }

  override def close(): Unit = {
    CloseLock.synchronized {
      closed = true
      nextTask.foreach(_.cancel(true))
    }
    ()
  }
}
