package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal
import java.util.concurrent.Executors
import scala.reflect.ClassTag
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try
import scala.util.Success
import scala.util.Failure

/** An AutoUpdatingVar based on the JDK's ScheduledExecutorService.
  *
  * By default, a JdkAutoUpdatingVar starts a new thread to handle its updates. If you are running
  * many JdkAutoUpdatingVars, you may want to consider providing a shared ScheduledExecutorService
  * to them.
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
  * @param executorOverride
  *   If present, will be used instead of starting a new thread.
  */
class JdkAutoUpdatingVar[T](
    updateVar: => T,
    updateInterval: UpdateInterval[T],
    updateAttemptStrategy: UpdateAttemptStrategy,
    varNameOverride: Option[String] = None,
    handleInitializationError: PartialFunction[Throwable, T] = PartialFunction.empty,
    executorOverride: Option[ScheduledExecutorService] = None
)(implicit ct: ClassTag[T])
    extends AutoUpdatingVar[Future, T] {

  private val log = LoggerFactory.getLogger(getClass)

  private val executor = executorOverride.getOrElse(Executors.newScheduledThreadPool(1))

  private val varName = varNameOverride match {
    case Some(n) => n
    case None    => ct.runtimeClass.getSimpleName
  }

  override def ready: Future[Unit] = _ready.future

  override def latest: T = variable.getOrElse(throw UnreadyAutoUpdatingVarException)

  override def close(): Unit = {
    log.info(s"$this: Shutting down")
    if (executorOverride.isEmpty)
      executor.shutdownNow()
    ()
  }

  override def toString: String = s"JdkAutoUpdatingVar($varName)"

  log.info(s"$this: Starting. ${updateAttemptStrategy.description}")

  @volatile private var variable: Option[T] = None

  private val _ready = Promise[Unit]()

  executor.schedule(
    new Runnable {
      def run(): Unit = {
        val tryV =
          Try(try {
            try {
              updateVar
            } catch {
              case NonFatal(e) =>
                log.error(s"$this: Failed to initialize var", e)
                throw e
            }
          } catch (handleInitializationError))

        tryV match {
          case Success(value) =>
            variable = Some(value)
            _ready.complete(Success(()))
            log.info(s"$this: Successfully initialized")
            scheduleUpdate(updateInterval.duration(value))
          case Failure(e) =>
            _ready.complete(Failure(e))
        }
      }
    },
    0,
    TimeUnit.NANOSECONDS
  )

  private def scheduleUpdate(nextUpdate: FiniteDuration): Unit = {
    log.info(s"$this: Scheduling update of var in: $nextUpdate")

    executor.schedule(new UpdateVar(1), nextUpdate.length, nextUpdate.unit)
    ()
  }

  private class UpdateVar(attempt: Int) extends Runnable {
    def run(): Unit = {
      try {
        val newV = updateVar
        variable = Some(newV)
        log.info(s"$this: Successfully updated")
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
