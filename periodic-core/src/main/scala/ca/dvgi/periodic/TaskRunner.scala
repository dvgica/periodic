package ca.dvgi.periodic

import org.slf4j.LoggerFactory

class TaskRunner(runner: Runner)(
    task: => Unit,
    taskName: String,
    runInterval: UpdateInterval.Static,
    runAttemptStrategy: UpdateAttemptStrategy
) extends AutoCloseable {

  private val log = LoggerFactory.getLogger(s"TaskRunner[$taskName]")

  log.info(s"Starting. ${runAttemptStrategy.description}")

  runner.start(
    log,
    () => task,
    taskName,
    runInterval,
    runAttemptStrategy
  )

  override def close(): Unit = {
    runner.close()
    log.info(s"Shut down sucessfully")
  }
}
