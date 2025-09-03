package ca.dvgi.periodic.ox

import ca.dvgi.periodic.*
import org.slf4j.Logger
import _root_.ox.*

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import scala.util.control.NonFatal


class OxPeriodic(using Ox) extends Periodic[Identity, Fork] {
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
  ): Unit = ???

  def close(): Unit = ()
}
