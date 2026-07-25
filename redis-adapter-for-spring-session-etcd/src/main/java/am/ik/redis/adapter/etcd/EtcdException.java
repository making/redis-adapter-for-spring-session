package am.ik.redis.adapter.etcd;

/**
 * Thrown when etcd cannot be reached, refuses a request, or answers something this
 * backend cannot read.
 *
 * <p>
 * It is deliberately not a {@code KeyValueStore} concept: the SPI names only the failures
 * a client can do something about — a type mismatch, and a value the backend will not
 * take ({@code am.ik.redis.adapter.store.ValueTooLargeException}, which a refusal for
 * size is raised as instead of this) — and everything else a backend can fail with is its
 * own business. The command layer turns any other runtime exception into
 * {@code ERR internal error} and logs it, which is the right answer to an unreachable
 * store — the client sees a failure, the operator sees the cause, and no session is
 * silently lost.
 */
public final class EtcdException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 */
	public EtcdException(String message) {
		super(message);
	}

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 * @param cause the failure underneath, typically an {@code IOException}
	 */
	public EtcdException(String message, Throwable cause) {
		super(message, cause);
	}

}
