package ca.dvgi.periodic.ox

import ca.dvgi.periodic.*
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

class OxAutoUpdatingVarTest extends AutoUpdatingVarTestsFuture[Future] {

  implicit var actorSystem: ActorSystem = _
  implicit var ec: ExecutionContext = _

  override def beforeAll(): Unit = {
    actorSystem = ActorSystem()
    ec = actorSystem.dispatcher
  }

  override def afterAll(): Unit = {
    val _ = actorSystem.terminate()
  }

  def evalU[T](ut: Future[T]): T = Await.result(ut, Duration.Inf)

  def pureU(thunk: => Int): Future[Int] = Future(thunk)

  def periodicBuilder(): () => Periodic[Future, Future] =
    () => PekkoStreamsPeriodic()

  testAll(periodicBuilder())
}
