package ca.dvgi.periodic

import munit.FunSuite
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Success
import scala.concurrent.Await

trait AutoUpdatingVarTestsFuture[U[_]] extends FunSuite {

  case object TestException extends RuntimeException

  class VarHolder {
    private var v = 1
    def get: U[Int] = pureU {
      val r = v
      v = v + 1
      r
    }
  }

  class VarErrorHolder {
    var attempts = 0
    def get: U[Int] = pureU {
      attempts = attempts + 1
      sys.error("test exception")
    }
  }

  def pureU(thunk: => Int): U[Int]

  def evalU[T](ut: U[T]): T

  def testAll(periodic: () => Periodic[U, Future, Int])(implicit
      loc: munit.Location
  ): Unit = {
    implicit val per = periodic
    testBasicsWithBlocking()

    testAdjustsUpdateInterval()

    testReturnsFailedReady()

    testThrowsFromLatest()

    testThrowsFromConstructor()

    testHandlesInititializationErrors()

    testInfiniteReattempts()

    testFiniteReattempts()
  }

  def testBasicsWithBlocking(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {
    FunFixture(
      _ => {
        val holder = new VarHolder
        val v = new AutoUpdatingVar(periodic())(
          holder.get,
          UpdateInterval.Static(1.seconds),
          AttemptStrategy.Infinite(1.second),
          Some(1.second)
        )
        (v, holder)
      },
      (f: (AutoCloseable, VarHolder)) => f._1.close()
    )
      .test("periodically updates the var, blocking on start, and closes") { case (v, holder) =>
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
        assertEquals(evalU(holder.get), 4)
      }
  }

  def testAdjustsUpdateInterval(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    FunFixture(
      _ => {
        val holder = new VarHolder()
        val v = new AutoUpdatingVar(periodic())(
          holder.get,
          UpdateInterval.Dynamic((i: Int) => i * 1.second),
          AttemptStrategy.Infinite(1.second),
          Some(1.second)
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
  }

  def testReturnsFailedReady(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    FunFixture(
      _ => {
        new AutoUpdatingVar(periodic())(
          pureU(throw TestException),
          UpdateInterval.Static(1.seconds),
          AttemptStrategy.Infinite(1.second)
        )
      },
      (f: AutoCloseable) => f.close()
    ).test("returns a failed future from ready if the first update fails") { v =>
      intercept[TestException.type] { Await.result(v.ready, 1.second) }
    }
  }

  def testThrowsFromLatest(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    FunFixture(
      _ => {
        new AutoUpdatingVar(periodic())(
          pureU {
            Thread.sleep(1000)
            1
          },
          UpdateInterval.Static(1.seconds),
          AttemptStrategy.Infinite(1.second)
        )
      },
      (f: AutoCloseable) => f.close()
    ).test("throws an exception if latest called before var is initialized") { v =>
      intercept[UnreadyAutoUpdatingVarException.type] { v.latest }
    }
  }

  def testThrowsFromConstructor(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    test(
      "returns a failed future from constructor if the first update fails and instructed to block"
    ) {
      intercept[TestException.type] {
        new AutoUpdatingVar(periodic())(
          pureU(throw TestException),
          UpdateInterval.Static(1.seconds),
          AttemptStrategy.Infinite(1.second),
          Some(1.second)
        )
      }
    }
  }

  def testHandlesInititializationErrors(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    FunFixture(
      _ => {
        new AutoUpdatingVar(periodic())(
          pureU(throw TestException),
          UpdateInterval.Static(1.seconds),
          AttemptStrategy.Infinite(1.second),
          Some(1.second),
          { case _ =>
            pureU(42)
          }
        )
      },
      (f: AutoCloseable) => f.close()
    ).test(
      "handles initialization errors"
    ) { v =>
      assertEquals(v.latest, 42)
    }
  }

  def testInfiniteReattempts(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    FunFixture(
      _ => {
        val holder = new VarErrorHolder
        val v =
          new AutoUpdatingVar(periodic())(
            holder.get,
            UpdateInterval.Static(1.second),
            AttemptStrategy.Infinite(1.second),
            Some(1.second),
            { case _ =>
              pureU(42)
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
  }

  def testFiniteReattempts(
  )(implicit
      loc: munit.Location,
      periodic: () => Periodic[U, Future, Int]
  ): Unit = {

    var terminated = false
    FunFixture(
      _ => {
        val holder = new VarErrorHolder
        val v =
          new AutoUpdatingVar(periodic())(
            holder.get,
            UpdateInterval.Static(1.second),
            AttemptStrategy
              .Finite(1.second, 2, AttemptExhaustionBehavior.Custom(_ => terminated = true)),
            Some(1.second),
            { case _ =>
              pureU(42)
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
  }
}
