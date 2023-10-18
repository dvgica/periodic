package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration

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
