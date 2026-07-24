package am.ik.redis.adapter.store;

/**
 * Thrown by a {@link KeyValueStore} mutation when the target key already holds a value of
 * a different type (for example appending a string to a key that holds a hash).
 *
 * <p>
 * The name and message are deliberately Redis-agnostic: the SPI does not know about the
 * RESP {@code WRONGTYPE} error. The command layer catches this and formats the wire
 * error. The failing mutation leaves the store unchanged (it is thrown before any
 * modification is committed).
 */
public final class TypeMismatchException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message a description of the attempted operation and the conflict
	 */
	public TypeMismatchException(String message) {
		super(message);
	}

}
