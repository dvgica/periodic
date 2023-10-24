package ca.dvgi.periodic.pekko.stream

import ca.dvgi.periodic._
import scala.concurrent.duration._
import scala.concurrent.Future
import org.apache.pekko.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.ExecutionContext

class PekkoStreamsAutoUpdaterTest extends AutoUpdaterTestsFuture[Future] {

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

  def autoUpdaterBuilder(): Option[Duration] => PekkoStreamsAutoUpdater[Int] =
    new PekkoStreamsAutoUpdater[Int](_)(actorSystem)

  testAll(autoUpdaterBuilder())
}
