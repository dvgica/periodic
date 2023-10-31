package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration

sealed trait AttemptStrategy {
  def attemptInterval: FiniteDuration
  def description: String
}

object AttemptStrategy {
  case class Infinite(attemptInterval: FiniteDuration) extends AttemptStrategy {
    val description = s"Attempt update indefinitely every $attemptInterval"
  }

  case class Finite(
      attemptInterval: FiniteDuration,
      maxAttempts: Int,
      attemptExhaustionBehavior: AttemptExhaustionBehavior = AttemptExhaustionBehavior.Terminate()
  ) extends AttemptStrategy {
    require(maxAttempts > 0)

    val description =
      s"Attempt update a maximum of $maxAttempts times every $attemptInterval; when attempts are exhausted: ${attemptExhaustionBehavior.description}"
  }
}
