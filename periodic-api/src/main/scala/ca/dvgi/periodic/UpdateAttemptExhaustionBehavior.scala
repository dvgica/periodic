package ca.dvgi.periodic

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
