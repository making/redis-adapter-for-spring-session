package am.ik.redis.adapter.foundationdb;

/**
 * Thrown when FoundationDB cannot be reached, refuses a request, or answers something
 * this backend cannot read.
 *
 * <p>
 * It is deliberately not a {@code KeyValueStore} concept: the SPI names only the failures
 * a client can do something about — a type mismatch, and a value the backend will not
 * take ({@code am.ik.redis.adapter.store.ValueTooLargeException}, which the three size
 * refusals are raised as instead of this) — and everything else a backend can fail with
 * is its own business. The command layer turns any other runtime exception into
 * {@code ERR internal error} and logs it, which is the right answer to an unreachable
 * cluster: the client sees a failure, the operator sees the cause, and no session is
 * silently lost.
 *
 * <p>
 * The one failure worth recognizing in it is a <strong>timeout</strong>. A FoundationDB
 * read against a cluster that is not there waits for ever by default, so every
 * transaction this backend opens carries a deadline; when that deadline passes the
 * failure arrives here rather than as a command that never answers.
 */
public final class FoundationDbException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 */
	public FoundationDbException(String message) {
		super(message);
	}

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 * @param cause the failure underneath, typically an {@code FDBException}
	 */
	public FoundationDbException(String message, Throwable cause) {
		super(message, cause);
	}

}
