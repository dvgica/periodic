package ca.dvgi.periodic.jdk

import ca.dvgi.periodic.Identity
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait Eval[F[_]] {
  def apply[A](fa: F[A]): A
}

object Eval {
  implicit val futureEval: Eval[Future] = new Eval[Future] {
    def apply[A](fa: Future[A]): A = Await.result(fa, Duration.Inf)
  }

  implicit val identityEval: Eval[Identity] = new Eval[Identity] {
    def apply[A](fa: Identity[A]): A = fa
  }
}
