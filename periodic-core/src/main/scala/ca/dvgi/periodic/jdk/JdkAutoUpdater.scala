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
abstract class JdkAutoUpdater[U[T], T](
    blockUntilReadyTimeout: Option[Duration] = None,
    executorOverride: Option[ScheduledExecutorService] = None
) extends AutoUpdater[U, Future, T] {

  protected def evalUpdate[A](ua: U[A]): A

  protected def pureUpdate[A](a: A): U[A]

  private val executor = executorOverride.getOrElse(Executors.newScheduledThreadPool(1))

  private val periodic = new JdkPeriodic[U, T]("var update", evalUpdate(_), executor)

  @volatile private var variable: Option[T] = None

  override def start(
      log: Logger,
      updateVar: () => U[T],
      updateInterval: UpdateInterval[T],
      updateAttemptStrategy: UpdateAttemptStrategy,
      handleInitializationError: PartialFunction[Throwable, U[T]]
  ): Future[Unit] = {
    val ready = Promise[Unit]()

    executor.schedule(
      new Runnable {
        def run(): Unit = {
          val tryV =
            Try(try {
              try {
                log.info("Attempting initialization...")
                evalUpdate(updateVar())
              } catch {
                case NonFatal(e) =>
                  log.error("Failed to initialize var", e)
                  throw e
              }
            } catch (handleInitializationError.andThen(evalUpdate _)))

          tryV match {
            case Success(value) =>
              variable = Some(value)
              ready.complete(Success(()))
              log.info("Successfully initialized")
              periodic.start(
                log,
                updateInterval.duration(value),
                () => {
                  val ut = updateVar()
                  val t = evalUpdate(ut)
                  variable = Some(t)
                  ut
                },
                ut => {
                  val t = evalUpdate(ut)
                  pureUpdate(updateInterval.duration(t))
                },
                updateAttemptStrategy
              )
            case Failure(e) =>
              ready.complete(Failure(e))
          }
        }
      },
      0,
      TimeUnit.NANOSECONDS
    )

    blockUntilReadyTimeout match {
      case Some(timeout) =>
        Try(Await.result(ready.future, timeout)) match {
          case Success(_)         => Future.successful(())
          case Failure(exception) => throw exception
        }
      case None => ready.future
    }
  }

  override def latest: Option[T] = variable

  override def close(): Unit = {
    periodic.close()
    if (executorOverride.isEmpty) {
      val _ = executor.shutdownNow()
    }
  }
}
