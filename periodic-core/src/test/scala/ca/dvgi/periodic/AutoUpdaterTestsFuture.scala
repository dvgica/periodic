package ca.dvgi.periodic

import munit.FunSuite
import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Success

trait AutoUpdaterTestsFuture[U[_]] extends FunSuite {

  trait GenericVarHolder {
    def get: U[Int]
  }

  def evalU[T](ut: U[T]): T

  def testBasicsWithBlocking(
      autoUpdater: AutoUpdater[U, Future, Int],
      holder: GenericVarHolder
  )(implicit loc: munit.Location): Unit = {
    FunFixture(
      _ => {
        val v = new AutoUpdatingVar(autoUpdater)(
          holder.get,
          UpdateInterval.Static(1.seconds),
          UpdateAttemptStrategy.Infinite(1.second)
        )
        (v, holder)
      },
      (f: (AutoCloseable, GenericVarHolder)) => f._1.close()
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

}
