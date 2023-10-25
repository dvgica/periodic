package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.slf4j.Logger
import java.util.concurrent.ScheduledFuture

abstract class JdkRunner(
    executorOverride: Option[ScheduledExecutorService] = None
) extends Runner {

  private val executor = executorOverride.getOrElse(Executors.newScheduledThreadPool(1))

  private case object CloseLock

  @volatile private var closed = false

  @volatile private var nextTask: Option[ScheduledFuture[_]] = None

  override def start(
      log: Logger,
      task: () => Unit,
      taskName: String,
      runInterval: UpdateInterval.Static,
      runAttemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    scheduleUpdate(0.nano)(
      log,
      task,
      taskName,
      runInterval,
      runAttemptStrategy
    )
  }

  override def close(): Unit = {
    CloseLock.synchronized {
      closed = true
      nextTask.foreach(_.cancel(true))
    }
    if (executorOverride.isEmpty) {
      val _ = executor.shutdownNow()
    }
    ()
  }

  private def scheduleUpdate(nextUpdate: FiniteDuration)(implicit
      log: Logger,
      task: () => Unit,
      taskName: String,
      runInterval: UpdateInterval.Static,
      runAttemptStrategy: UpdateAttemptStrategy
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
      task: () => Unit,
      taskName: String,
      runInterval: UpdateInterval.Static,
      runAttemptStrategy: UpdateAttemptStrategy
  ) extends Runnable {
    def run(): Unit = {
      log.info("Attempting var update...")
      try {
        task()
        log.info("Successfully updated")
        scheduleUpdate(runInterval.duration(()))
      } catch {
        case NonFatal(e) =>
          runAttemptStrategy match {
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
        task: () => Unit,
        runInterval: UpdateInterval.Static,
        runAttemptStrategy: UpdateAttemptStrategy
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
