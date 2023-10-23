package ca.dvgi.periodic.pekko

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

/** An AutoUpdater based on Pekko.
  *
  * Recommended when Pekko is already in use, since it is completely non-blocking, does not require
  * additional resources, and will scale to many AutoUpdatingVars without any tuning.
  *
  * @param blockUntilReadyTimeout
  *   If specified, will block the calling AutoUpdatingVar instantiation until it succeeds, fails,
  *   or the timeout is reached.
  * @param actorSystem
  *   An ActorSystem used to update the var.
  */
class PekkoAutoUpdater[T](blockUntilReadyTimeout: Option[Duration] = None)(implicit
    actorSystem: ActorSystem
) extends AutoUpdater[Future, Future, T] {
  import PekkoAutoUpdater._

  implicit private val ec: ExecutionContext = actorSystem.dispatcher

  private val killSwitch = KillSwitches.shared("close")

  @volatile private var variable: Option[T] = None

  def start(
      log: Logger,
      updateVar: () => Future[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy,
      handleInitializationError: PartialFunction[Throwable, Future[T]]
  ): Future[Unit] = {
    log.info("Attempting initialization...")

    val ready = updateVar()
      .recover { case NonFatal(e) =>
        log.error(s"Failed to initialize var", e)
        throw e
      }
      .recoverWith(handleInitializationError)
      .map { v =>
        log.info(s"Successfully initialized")
        variable = Some(v)
        scheduleUpdate(updateInterval.duration(v))(
          log,
          updateVar,
          updateInterval,
          updateAttemptStrategy
        )
        ()
      }

    blockUntilReadyTimeout match {
      case Some(timeout) =>
        Try(Await.result(ready, timeout)) match {
          case Success(_)         => Future.successful(())
          case Failure(exception) => throw exception
        }
      case None => ready
    }
  }

  def latest: Option[T] = variable

  def close(): Unit = {
    killSwitch.shutdown()
  }

  private def scheduleUpdate(nextUpdate: FiniteDuration)(implicit
      log: Logger,
      updateVar: () => Future[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy
  ): Unit = {
    log.info(s"Scheduling update of var in: $nextUpdate")

    val maxUpdateAttempts = updateAttemptStrategy match {
      case UpdateAttemptStrategy.Infinite(_) =>
        -1 // signifies infinite attempts to recoverWithRetries
      case s: UpdateAttemptStrategy.Finite =>
        s.maxAttempts
    }

    val varUpdate = buildVarSource(nextUpdate)
      .recoverWithRetries(
        attempts = maxUpdateAttempts,
        { case e: UpdateException =>
          log.warn(
            s"Unhandled exception when trying to update var, retrying in ${updateAttemptStrategy.attemptInterval}",
            e.cause
          )
          buildVarSource(updateAttemptStrategy.attemptInterval)
        }
      )

    varUpdate
      .via(killSwitch.flow)
      .runForeach { newVar =>
        variable = Some(newVar)
        scheduleUpdate(updateInterval.duration(newVar))
      }
      .failed
      .foreach {
        case e: UpdateException =>
          log.error(s"Var update retries exhausted! Final attempt exception", e.cause)
          updateAttemptStrategy match {
            case s: UpdateAttemptStrategy.Finite => s.attemptExhaustionBehavior.run(log)
            case _                               =>
              // should never happen
              log.error(
                "Somehow exhausted infinite attempts! Something is very wrong. Attempting to exit..."
              )
              UpdateAttemptExhaustionBehavior.Terminate().run(log)
          }
        case e =>
          log.error(s"Unhandled library exception, attempting to exit...", e)
          UpdateAttemptExhaustionBehavior.Terminate().run(log)
      }
  }

  private def buildVarSource(nextUpdate: FiniteDuration)(implicit
      log: Logger,
      updateVar: () => Future[T]
  ): Source[T, NotUsed] = {
    Source
      .single(())
      .delay(nextUpdate)
      .mapAsync(1) { _ =>
        updateVar()
          .map { v =>
            log.info(s"Successfully updated")
            v
          }
          .recover { case NonFatal(e) =>
            throw new UpdateException(e)
          }
      }
  }
}

object PekkoAutoUpdater {
  private case class UpdateException(cause: Throwable) extends RuntimeException
}
