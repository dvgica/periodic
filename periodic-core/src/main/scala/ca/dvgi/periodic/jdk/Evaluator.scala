package ca.dvgi.periodic.jdk

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

trait Evaluator[F[_]] {
  def apply[A](fa: F[A]): A
}

object Evaluator {
  implicit val futureEvaluator: Evaluator[Future] = new Evaluator[Future] {
    def apply[A](fa: Future[A]): A = Await.result(fa, Duration.Inf)
  }

  implicit val identityEvaluator: Evaluator[Identity] = new Evaluator[Identity] {
    def apply[A](fa: Identity[A]): A = fa
  }
}
