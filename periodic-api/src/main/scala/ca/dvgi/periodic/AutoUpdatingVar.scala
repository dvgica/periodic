package ca.dvgi.periodic

/** A variable that updates itself. `latest` can be called from multiple threads, which are all
  * guaranteed to get the latest var.
  *
  * An AutoUpdatingVar attempts to get the variable immediately upon class instantiation. If this
  * fails, there are no further attempts, and the effect returned by the `ready` method will
  * complete unsuccesfully. If it succeeds, the effect completes successfully and `latest` can be
  * safely called.
  *
  * Failed updates (those that throw an exception) may be retried with various configurations.
  * However, if the initial update of the var during class instantiationfails
  *
  * A successful update schedules the next update, with an interval that can vary based on the
  * just-updated var.
  */
trait AutoUpdatingVar[F[_], T] extends AutoCloseable {

  /** @return
    *   An effect which, once successfully completed, signifies that the AutoUpdatingVar has a
    *   value, i.e. `latest` can be called and no exception will be thrown.
    */
  def ready: F[Unit]

  /** Wait for `ready` to be completed before calling this method.
    *
    * @return
    *   The latest value of the variable. Calling this method is thread-safe.
    * @throws UnreadyAutoUpdatingVarException
    *   if there is not yet a value to return
    */
  def latest: T
}
