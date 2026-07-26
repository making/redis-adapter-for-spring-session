package am.ik.redis.adapter.dynamodb;

/**
 * Thrown when DynamoDB cannot be reached, refuses a request for a reason retrying will
 * not cure, or a contended key keeps changing for longer than this backend retries.
 *
 * <p>
 * It is deliberately not a {@code KeyValueStore} concept: the SPI names only the failures
 * a client can do something about — a type mismatch, and a value the backend will not
 * take ({@code am.ik.redis.adapter.store.ValueTooLargeException}, which DynamoDB's 400 KB
 * item refusal is raised as instead of this) — and everything else a backend can fail
 * with is its own business. The command layer turns any other runtime exception into
 * {@code ERR internal error} and logs it, which is the right answer to an unreachable
 * table — the client sees a failure, the operator sees the cause, and no session is
 * silently lost.
 */
public final class DynamoDbBackendException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 */
	public DynamoDbBackendException(String message) {
		super(message);
	}

	/**
	 * Creates a new exception.
	 * @param message what could not be done
	 * @param cause the refusal underneath, typically the SDK's own exception
	 */
	public DynamoDbBackendException(String message, Throwable cause) {
		super(message, cause);
	}

}
