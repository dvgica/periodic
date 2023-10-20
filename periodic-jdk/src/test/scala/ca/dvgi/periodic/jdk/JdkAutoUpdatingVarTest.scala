package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.util.Success
import scala.concurrent.Await
import java.util.concurrent.Executors

class JdkAutoUpdatingVarTest extends munit.FunSuite {

  class VarHolder {
    private var v = 1
    def get: Int = {
      val r = v
      v = v + 1
      r
    }
  }

  class VarErrorHolder {
    var attempts = 0
    def get: Int = {
      attempts = attempts + 1
      sys.error("test exception")
    }
  }

  test("periodically updates the var, blockng on start, and closes") {
    val holder = new VarHolder

    val v = new JdkAutoUpdatingVar(
      holder.get,
      UpdateInterval.Static(1.seconds),
      UpdateAttemptStrategy.Infinite(1.second),
      Some(1.second)
    )

    assert(v.ready.isCompleted)
    assertEquals(v.ready.value, Some(Success(())))

    assertEquals(v.latest, 1)
    assertEquals(v.latest, 1) // value should still be cached

    Thread.sleep(1100)

    assertEquals(v.latest, 2)
    assertEquals(v.latest, 2)

    Thread.sleep(1000)

    assertEquals(v.latest, 3)
    assertEquals(v.latest, 3)

    v.close()

    Thread.sleep(1000)
    assertEquals(holder.get, 4)
  }

  test("adjusts the update interval based on the returned value") {
    val holder = new VarHolder

    val v = new JdkAutoUpdatingVar(
      holder.get,
      UpdateInterval.Dynamic((i: Int) => i * 1.second),
      UpdateAttemptStrategy.Infinite(1.second),
      Some(1.second)
    )

    assert(v.ready.isCompleted)
    assertEquals(v.ready.value, Some(Success(())))

    assertEquals(v.latest, 1)
    assertEquals(v.latest, 1) // value should still be cached

    Thread.sleep(1100)

    assertEquals(v.latest, 2)
    assertEquals(v.latest, 2)

    Thread.sleep(1000)

    assertEquals(v.latest, 2) // still 2 since update shouldn't have happened yet

    Thread.sleep(1000)

    assertEquals(v.latest, 3)

    v.close()
  }

  test("returns a failed future from ready if the first update fails") {
    case object TestException extends RuntimeException

    val v = new JdkAutoUpdatingVar[Int](
      throw TestException,
      UpdateInterval.Static(1.seconds),
      UpdateAttemptStrategy.Infinite(1.second)
    )

    val _ = intercept[TestException.type] { Await.result(v.ready, 1.second) }
    v.close
  }

  test("throws an exception if latest called before var is initialized") {

    val v = new JdkAutoUpdatingVar(
      {
        Thread.sleep(1000)
        1
      },
      UpdateInterval.Static(1.seconds),
      UpdateAttemptStrategy.Infinite(1.second)
    )

    val _ = intercept[UnreadyAutoUpdatingVarException.type] { v.latest }
    v.close
  }

  test(
    "returns a failed future from constructor if the first update fails and instructed to block"
  ) {
    case object TestException extends RuntimeException

    intercept[TestException.type] {
      new JdkAutoUpdatingVar[Int](
        throw TestException,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second),
        Some(1.second)
      )
    }
  }

  test(
    "handles initialization errors"
  ) {
    case object TestException extends RuntimeException

    val v =
      new JdkAutoUpdatingVar(
        throw TestException,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second),
        Some(1.second),
        { case _ =>
          42
        }
      )

    assertEquals(v.latest, 42)
    v.close()
  }

  test("does infinite reattempts") {
    val holder = new VarErrorHolder
    val v =
      new JdkAutoUpdatingVar(
        holder.get,
        UpdateInterval.Static(1.second),
        UpdateAttemptStrategy.Infinite(1.second),
        Some(1.second),
        { case _ =>
          42
        }
      )

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 1)

    Thread.sleep(1100)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 2)

    Thread.sleep(1000)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 3)

    v.close()
  }

  test("does finite reattempts") {
    val holder = new VarErrorHolder
    var terminated = false
    val v =
      new JdkAutoUpdatingVar(
        holder.get,
        UpdateInterval.Static(1.second),
        UpdateAttemptStrategy
          .Finite(1.second, 2, UpdateAttemptExhaustionBehavior.Custom(_ => terminated = true)),
        Some(1.second),
        { case _ =>
          42
        }
      )

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 1)
    assertEquals(terminated, false)

    Thread.sleep(1100)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 2)
    assertEquals(terminated, false)

    Thread.sleep(1000)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 3)
    assertEquals(terminated, true)

    Thread.sleep(1000)

    assertEquals(holder.attempts, 3)

    v.close()
  }

  test("can use an external SchedulerExecutorService") {
    val holder = new VarHolder
    val ses = Executors.newScheduledThreadPool(1)

    val v = new JdkAutoUpdatingVar(
      holder.get,
      UpdateInterval.Static(2.seconds),
      UpdateAttemptStrategy.Infinite(1.second),
      Some(1.second),
      executorOverride = Some(ses)
    )

    assertEquals(v.latest, 1)

    v.close()
    assert(!ses.isShutdown())

    Thread.sleep(5000)
    assertEquals(holder.get, 2)
  }
}
