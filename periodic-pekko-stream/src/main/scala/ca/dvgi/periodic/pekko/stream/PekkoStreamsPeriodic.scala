package ca.dvgi.periodic.pekko.stream

import ca.dvgi.periodic._
import org.slf4j.Logger
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.Source
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.util.Success
import scala.util.Failure
import scala.util.Try

/** A Periodic based on Pekko Streams.
  *
  * Recommended when Pekko is already in use, since it is completely non-blocking, does not require
  * additional resources, and will scale to many usages without any tuning.
  *
  * @param actorSystem
  *   An ActorSystem used to execute periodic actions.
  */
class PekkoStreamsPeriodic[T](implicit
    actorSystem: ActorSystem
) extends Periodic[Future, Future, T] {
  import PekkoStreamsPeriodic._

  implicit private val ec: ExecutionContext = actorSystem.dispatcher

  private val killSwitch = KillSwitches.shared("close")

  override def scheduleNow(
      log: Logger,
      operationName: String,
      fn: () => Future[T],
      onSuccess: T => Unit,
      handleError: PartialFunction[Throwable, Future[T]],
      blockUntilCompleteTimeout: Option[Duration] = None
  ): Future[Unit] = {
    log.info(s"Attempting to $operationName...")

    val result = fn()
      .recover { case NonFatal(e) =>
        log.warn(s"Failed to $operationName", e)
        throw e
      }
      .recoverWith { case NonFatal(t) =>
        handleError.applyOrElse(t, (t: Throwable) => throw t)
      }
      .map { v =>
        onSuccess(v)
        log.info(s"Successfully completed $operationName")
        ()
      }

    blockUntilCompleteTimeout match {
      case Some(timeout) =>
        Try(Await.result(result, timeout)) match {
          case Success(_)         => Future.successful(())
          case Failure(exception) => throw exception
        }
      case None => result
    }
  }

  override def scheduleRecurring(
      log: Logger,
      operationName: String,
      initialDelay: FiniteDuration,
      fn: () => Future[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit = {
    scheduleNext(initialDelay)(log, operationName, fn, onSuccess, interval, attemptStrategy)
  }

  override def close(): Unit = {
    killSwitch.shutdown()
  }

  private def scheduleNext(delay: FiniteDuration)(implicit
      log: Logger,
      operationName: String,
      fn: () => Future[T],
      onSuccess: T => Unit,
      interval: T => FiniteDuration,
      attemptStrategy: AttemptStrategy
  ): Unit = {
    log.info(s"Scheduling next $operationName in: $delay")

    val maxAttempts = attemptStrategy match {
      case AttemptStrategy.Infinite(_) =>
        -1 // signifies infinite attempts to recoverWithRetries
      case s: AttemptStrategy.Finite =>
        // maxAttempts required to be > 0
        s.maxAttempts - 1
    }

    val runFn = buildRunFnSource(delay)
      .recoverWithRetries(
        attempts = maxAttempts,
        { case e: RunFnException =>
          log.warn(
            s"Unhandled exception during $operationName, retrying in ${attemptStrategy.attemptInterval}",
            e.cause
          )
          buildRunFnSource(attemptStrategy.attemptInterval)
        }
      )

    runFn
      .via(killSwitch.flow)
      .runForeach { result =>
        log.info(s"Successfully executed $operationName")
        onSuccess(result)
        scheduleNext(interval(result))
      }
      .failed
      .foreach {
        case e: RunFnException =>
          log.error(
            s"${operationName.capitalize} retries exhausted! Final attempt exception",
            e.cause
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
        case e =>
          log.error(s"Unhandled library exception, attempting to exit...", e)
          AttemptExhaustionBehavior.Terminate().run(log)
      }
  }

  private def buildRunFnSource(delay: FiniteDuration)(implicit
      log: Logger,
      operationName: String,
      fn: () => Future[T]
  ): Source[T, NotUsed] = {
    Source
      .single(())
      .delay(delay)
      .mapAsync(1) { _ =>
        log.info(s"Attempting $operationName...")
        fn()
          .map { v =>
            v
          }
          .recover { case NonFatal(e) =>
            throw new RunFnException(e)
          }
      }
  }
}

object PekkoStreamsPeriodic {
  def apply[T]()(implicit
      actorSystem: ActorSystem
  ): PekkoStreamsPeriodic[T] = new PekkoStreamsPeriodic[T]

  private case class RunFnException(cause: Throwable) extends RuntimeException
}
