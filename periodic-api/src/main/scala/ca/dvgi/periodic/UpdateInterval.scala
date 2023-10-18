package ca.dvgi.periodic

import scala.concurrent.duration.FiniteDuration

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
