package ca.dvgi.periodic.jdk

import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.util.Success
import scala.concurrent.Await
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class JdkAutoUpdaterTest extends munit.FunSuite {

  case object TestException extends RuntimeException

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

  FunFixture(
    _ => {
      val holder = new VarHolder
      val v = new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(5.seconds)))(
        holder.get,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second)
      )
      (v, holder)
    },
    (f: (AutoCloseable, VarHolder)) => f._1.close()
  )
    .test("periodically updates the var, blockng on start, and closes") { case (v, holder) =>
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

  FunFixture(
    _ => {
      val holder = new VarHolder
      val v = new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(1.second)))(
        holder.get,
        UpdateInterval.Dynamic((i: Int) => i * 1.second),
        UpdateAttemptStrategy.Infinite(1.second)
      )
      (v, holder)
    },
    (f: (AutoCloseable, VarHolder)) => f._1.close()
  )
    .test("adjusts the update interval based on the returned value") { case (v, _) =>
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
    }

  FunFixture(
    _ => {
      new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int]())(
        throw TestException,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second)
      )
    },
    (f: AutoCloseable) => f.close()
  ).test("returns a failed future from ready if the first update fails") { v =>
    intercept[TestException.type] { Await.result(v.ready, 1.second) }
  }

  FunFixture(
    _ => {
      new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int]())(
        {
          Thread.sleep(1000)
          1
        },
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second)
      )
    },
    (f: AutoCloseable) => f.close()
  ).test("throws an exception if latest called before var is initialized") { v =>
    intercept[UnreadyAutoUpdatingVarException.type] { v.latest }
  }

  test(
    "returns a failed future from constructor if the first update fails and instructed to block"
  ) {
    intercept[TestException.type] {
      new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(1.second)))(
        throw TestException,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second)
      )
    }
  }

  FunFixture(
    _ => {
      new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(1.second)))(
        throw TestException,
        UpdateInterval.Static(1.seconds),
        UpdateAttemptStrategy.Infinite(1.second),
        { case _ =>
          42
        }
      )
    },
    (f: AutoCloseable) => f.close()
  ).test(
    "handles initialization errors"
  ) { v =>
    assertEquals(v.latest, 42)
  }

  FunFixture(
    _ => {
      val holder = new VarErrorHolder
      val v =
        new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(1.second)))(
          holder.get,
          UpdateInterval.Static(1.second),
          UpdateAttemptStrategy.Infinite(1.second),
          { case _ =>
            42
          }
        )
      (v, holder)
    },
    (f: (AutoCloseable, VarErrorHolder)) => f._1.close()
  ).test("does infinite reattempts") { case (v, holder) =>
    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 1)

    Thread.sleep(1100)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 2)

    Thread.sleep(1000)

    assertEquals(v.latest, 42)
    assertEquals(holder.attempts, 3)
  }

  var terminated = false
  FunFixture(
    _ => {
      val holder = new VarErrorHolder
      val v =
        new AutoUpdatingVar(new IdentityJdkAutoUpdater[Int](Some(1.second)))(
          holder.get,
          UpdateInterval.Static(1.second),
          UpdateAttemptStrategy
            .Finite(1.second, 2, UpdateAttemptExhaustionBehavior.Custom(_ => terminated = true)),
          { case _ =>
            42
          }
        )
      (v, holder)
    },
    (f: (AutoCloseable, VarErrorHolder)) => f._1.close()
  ).test("does finite reattempts") { case (v, holder) =>
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
  }

  FunFixture(
    _ => {
      val holder = new VarHolder
      val ses = Executors.newScheduledThreadPool(1)
      val v =
        new AutoUpdatingVar(
          new IdentityJdkAutoUpdater[Int](Some(1.second), executorOverride = Some(ses))
        )(
          holder.get,
          UpdateInterval.Static(2.seconds),
          UpdateAttemptStrategy.Infinite(1.second)
        )
      (v, holder, ses)
    },
    (f: (AutoCloseable, VarHolder, ScheduledExecutorService)) => {
      f._1.close()
      f._3.shutdownNow()
      ()
    }
  ).test("can use an external SchedulerExecutorService") { case (v, holder, ses) =>
    assertEquals(v.latest, 1)

    v.close()
    assert(!ses.isShutdown())

    Thread.sleep(5000)
    assertEquals(holder.get, 2)
  }
}
