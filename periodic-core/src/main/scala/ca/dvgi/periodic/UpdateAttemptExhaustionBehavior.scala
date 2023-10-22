package ca.dvgi.periodic

import org.slf4j.Logger

sealed trait UpdateAttemptExhaustionBehavior {
  def run: Logger => Unit
  def description: String
}

object UpdateAttemptExhaustionBehavior {
  case class Terminate(exitCode: Int = 1) extends UpdateAttemptExhaustionBehavior {
    def run: Logger => Unit = log => {
      log.error(
        s"Var update attempts exhausted, will now attempt to exit the process with exit code: $exitCode..."
      )
      sys.exit(exitCode)
    }

    val description: String = s"Terminate with exit code $exitCode"
  }

  case class Custom(run: Logger => Unit, descriptionOverride: Option[String] = None)
      extends UpdateAttemptExhaustionBehavior {
    val description: String = descriptionOverride.getOrElse("Run custom logic")
  }
}
