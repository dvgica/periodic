package ca.dvgi.periodic.jdk

import scala.concurrent.duration.FiniteDuration
import ca.dvgi.periodic.UpdateAttemptStrategy
import ca.dvgi.periodic.Periodic
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import scala.util.control.NonFatal
import org.slf4j.Logger
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure
import java.util.concurrent.TimeUnit
import scala.util.Try
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.Await

class JdkPeriodic[F[_], T](
    executorOverride: Option[ScheduledExecutorService] = None
)(implicit evalF: Evaluator[F])
    extends Periodic[F, Future, T] {

  private val executor = executorOverride.getOrElse(Executors.newScheduledThreadPool(1))

  private case object CloseLock

  @volatile private var closed = false

  @volatile private var nowTask: Option[ScheduledFuture[_]] = None

  @volatile private var recurringTask: Option[ScheduledFuture[_]] = None

  override def scheduleNow(
      log: Logger,
      operationName: String,
      fn: () => F[T],
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, F[T]],
      blockUntilCompleteTimeout: Option[Duration] = None
  ): Future[Unit] = {
    val ready = Promise[Unit]()

    CloseLock.synchronized {
      if (!closed) {
        nowTask = Some(
          executor.schedule(
            new Runnable {
              def run(): Unit = {
                val tryFn =
                  Try(try {
                    try {
                      log.info(s"Attempting to $operationName...")
                      evalF(fn())
                    } catch {
                      case NonFatal(e) =>
                        log.warn(s"Failed to $operationName", e)
                        throw e
                    }
                  } catch {
                    case NonFatal(t) =>
                      evalF(handleError.applyOrElse(t, (t: Throwable) => throw t))
                  })

                tryFn match {
                  case Success(value) =>
                    onSuccess(value)
                    ready.complete(Success(()))
                    log.info(s"Successfully completed $operationName")
                  case Failure(e) =>
                    ready.complete(Failure(e))
                }
              }
            },
            0,
            TimeUnit.NANOSECONDS
          )
        )
      } else {
        log.warn("Can't scheduleNow because JdkPeriodic is closing")
      }
    }

    blockUntilCompleteTimeout match {
      case Some(timeout) =>
        Try(Await.result(ready.future, timeout)) match {
          case Success(_)         => Future.successful(())
          case Failure(exception) => throw exception
        }
      case None => ready.future
    }
  }

  override def scheduleRecurring(
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => F[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    scheduleNext(initialDelay)(log, operationName, fn, onSuccess, interval, attemptStrategy)
  }

  override def close(): Unit = {
    CloseLock.synchronized {
      closed = true
      nowTask.foreach(_.cancel(true))
      recurringTask.foreach(_.cancel(true))
      if (executorOverride.isEmpty) {
        val _ = executor.shutdownNow()
      }
    }
    ()
  }

  private def scheduleNext(delay: FiniteDuration)(implicit
      log: Logger,
      operationName: String,
      fn: () => F[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    CloseLock.synchronized {
      if (!closed) {
        log.info(s"Scheduling next $operationName in: $delay")

        recurringTask = Some(
          executor.schedule(
            new FnRunnable(1),
            delay.length,
            delay.unit
          )
        )
      }
    }
    ()
  }

  private class FnRunnable(attempt: Int)(implicit
      log: Logger,
      operationName: String,
      fn: () => F[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: UpdateAttemptStrategy
  ) extends Runnable {
    def run(): Unit = {
      try {
        log.info(s"Attempting $operationName...")
        val result = evalF(fn())
        log.info(s"Successfully executed $operationName")
        onSuccess(result)
        scheduleNext(interval(result))
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
        onSuccess: T => Unit,
        interval: T => FiniteDuration,
        attemptStrategy: UpdateAttemptStrategy
    ): Unit = {
      log.warn(
        s"Unhandled exception when trying to $operationName, retrying in $delay",
        e
      )

      CloseLock.synchronized {
        if (!closed)
          recurringTask = Some(
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
}

object JdkPeriodic {
  def apply[F[_], T](
      executorOverride: Option[ScheduledExecutorService] = None
  )(implicit evalF: Evaluator[F]): JdkPeriodic[F, T] = {
    new JdkPeriodic[F, T](executorOverride)
  }
}
