package ca.dvgi.periodic

import org.slf4j.Logger

trait Runner extends AutoCloseable {
  def start(
      log: Logger,
      task: () => Unit,
      taskName: String,
      runInterval: UpdateInterval.Static,
      runAttemptStrategy: UpdateAttemptStrategy
  ): Unit

  def close(): Unit
}
