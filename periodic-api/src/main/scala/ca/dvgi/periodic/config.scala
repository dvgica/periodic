package ca.dvgi.periodic

import scala.concurrent.duration._
import org.slf4j.LoggerFactory

sealed trait UpdateAttemptExhaustionBehavior {
  def run: String => Unit
  def description: String
}
object UpdateAttemptExhaustionBehavior {
  case class Terminate(exitCode: Int = 1) extends UpdateAttemptExhaustionBehavior {
    private val log = LoggerFactory.getLogger(getClass)

    def run: String => Unit = name => {
      log.error(
        s"$name: Var update attempts exhausted, will now attempt to exit the process with exit code: $exitCode..."
      )
      sys.exit(exitCode)
    }

    val description: String = s"Terminate with exit code $exitCode"
  }
  case class Custom(run: String => Unit, descriptionOverride: Option[String] = None)
      extends UpdateAttemptExhaustionBehavior {
    val description: String = descriptionOverride.getOrElse("Run custom logic")
  }
}

sealed trait UpdateAttemptStrategy {
  def attemptInterval: FiniteDuration
  def description: String
}
object UpdateAttemptStrategy {
  case class Infinite(attemptInterval: FiniteDuration) extends UpdateAttemptStrategy {
    val description = s"Attempt update indefinitely every $attemptInterval"
  }
  case class Finite(
      attemptInterval: FiniteDuration,
      maxAttempts: Int,
      attemptExhaustionBehavior: UpdateAttemptExhaustionBehavior =
        UpdateAttemptExhaustionBehavior.Terminate()
  ) extends UpdateAttemptStrategy {
    val description =
      s"Attempt update a maximum of $maxAttempts times every $attemptInterval; when attempts are exhausted: ${attemptExhaustionBehavior.description}"
  }
}

sealed trait UpdateInterval[-T] {
  def duration(t: T): FiniteDuration
}
object UpdateInterval {
  case class Static(interval: FiniteDuration) extends UpdateInterval[Any] {
    override def duration(t: Any): FiniteDuration = interval
  }
  case class Dynamic[T](calculateUpdateInterval: T => FiniteDuration) extends UpdateInterval[T] {
    override def duration(t: T): FiniteDuration = calculateUpdateInterval(t)
  }
}
