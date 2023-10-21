package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import org.slf4j.Logger
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Await
import java.util.concurrent.ScheduledFuture

/** An AutoUpdater based on the JDK's ScheduledExecutorService.
  *
  * By default, a JdkAutoUpdater starts a new thread to handle its updates. If you are running many
  * JdkAutoUpdaters, you may want to consider providing a shared ScheduledExecutorService to them.
  *
  * @param blockUntilReadyTimeout
  *   If specified, will block the calling AutoUpdatingVar instantiation until it succeeds, fails,
  *   or the timeout is reached.
  * @param executorOverride
  *   If present, will be used instead of starting a new thread.
  */
class JdkAutoUpdater[T](
    blockUntilReadyTimeout: Option[Duration] = None,
    executorOverride: Option[ScheduledExecutorService] = None
) extends AutoUpdater[Identity, Future, T] {

  private val executor = executorOverride.getOrElse(Executors.newScheduledThreadPool(1))

  private case object CloseLock

  @volatile private var closed = false

  @volatile private var variable: Option[T] = None

  private val _ready = Promise[Unit]()

  @volatile private var nextTask: Option[ScheduledFuture[_]] = None

  override def start(
      log: Logger,
      updateVar: => T,
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy,
      handleInitializationError: PartialFunction[Throwable, T]
  ): Future[Unit] = {
    executor.schedule(
      new Runnable {
        def run(): Unit = {
          val tryV =
            Try(try {
              try {
                updateVar
              } catch {
                case NonFatal(e) =>
                  log.error("Failed to initialize var", e)
                  throw e
              }
            } catch (handleInitializationError))

          tryV match {
            case Success(value) =>
              variable = Some(value)
              _ready.complete(Success(()))
              log.info("Successfully initialized")
              scheduleUpdate(updateInterval.duration(value))(
                log,
                () => updateVar,
                updateInterval,
                updateAttemptStrategy
              )
            case Failure(e) =>
              _ready.complete(Failure(e))
          }
        }
      },
      0,
      TimeUnit.NANOSECONDS
    )

    blockUntilReadyTimeout.foreach { timeout =>
      Await.result(_ready.future, timeout)
    }

    _ready.future
  }

  override def latest: T = variable.getOrElse(throw UnreadyAutoUpdatingVarException)

  override def close(): Unit = {
    CloseLock.synchronized {
      closed = true
      nextTask.foreach(_.cancel(true))
    }
    if (executorOverride.isEmpty)
      executor.shutdownNow()
    ()
  }

  private def scheduleUpdate(nextUpdate: FiniteDuration)(implicit
      log: Logger,
      updateVar: () => T,
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    log.info(s"Scheduling update of var in: $nextUpdate")

    CloseLock.synchronized {
      if (!closed)
        nextTask = Some(
          executor.schedule(
            new UpdateVar(1),
            nextUpdate.length,
            nextUpdate.unit
          )
        )
    }
    ()
  }

  private class UpdateVar(attempt: Int)(implicit
      log: Logger,
      updateVar: () => T,
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy
  ) extends Runnable {
    def run(): Unit = {
      try {
        val newV = updateVar()
        variable = Some(newV)
        log.info("Successfully updated")
        scheduleUpdate(updateInterval.duration(newV))
      } catch {
        case NonFatal(e) =>
          updateAttemptStrategy match {
            case UpdateAttemptStrategy.Infinite(attemptInterval) =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(attemptInterval, maxAttempts, _)
                if attempt < maxAttempts =>
              reattempt(e, attemptInterval)
            case UpdateAttemptStrategy.Finite(_, _, attemptExhaustionBehavior) =>
              log.error("Var update attempts exhausted! Final attempt exception", e)
              attemptExhaustionBehavior.run(log)
          }
      }
    }

    private def reattempt(e: Throwable, delay: FiniteDuration)(implicit
        log: Logger,
        updateVar: () => T,
        updateInterval: UpdateInterval[T],
        updateAttemptStrategy: UpdateAttemptStrategy
    ): Unit = {
      log.warn(
        s"Unhandled exception when trying to update var, retrying in $delay",
        e
      )

      CloseLock.synchronized {
        if (!closed)
          nextTask = Some(
            executor.schedule(
              new UpdateVar(attempt + 1),
              delay.length,
              delay.unit
            )
          )
      }
      ()
    }
  }
}
