package am.ik.redis.adapter.store;

/**
 * Thrown by a {@link KeyValueStore} mutation when the value it was asked to store is
 * bigger than the backend will accept.
 *
 * <p>
 * It is a failure of the value rather than of the store: nothing is wrong, nothing is
 * unreachable, and repeating the write cannot make it land. That is the whole reason this
 * is not left to the generic failure path — a caller told {@code internal error} goes
 * looking for a bug in the adapter, when what it has to do is store less.
 *
 * <p>
 * Like {@link TypeMismatchException} the name and message are Redis-agnostic; the command
 * layer formats the wire error. A backend with no such limit — the in-memory one — never
 * throws this. The failing mutation leaves the store unchanged.
 */
public final class ValueTooLargeException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message what did not fit, and what the backend said about it
	 */
	public ValueTooLargeException(String message) {
		super(message);
	}

	/**
	 * Creates a new exception.
	 * @param message what did not fit, and what the backend said about it
	 * @param cause the refusal underneath
	 */
	public ValueTooLargeException(String message, Throwable cause) {
		super(message, cause);
	}

}
