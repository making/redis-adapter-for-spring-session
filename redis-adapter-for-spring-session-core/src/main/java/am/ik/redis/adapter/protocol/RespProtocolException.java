package am.ik.redis.adapter.protocol;

/**
 * Signals that inbound bytes violate the RESP framing rules (bad length, missing CRLF,
 * unexpected marker, exceeded limit, overflow, and so on).
 *
 * <p>
 * This is distinct from an {@link java.io.IOException}: an {@code IOException} means the
 * underlying transport failed, whereas this means the peer sent malformed protocol. The
 * server layer typically replies with an {@code ERR Protocol error} and closes the
 * connection.
 */
public final class RespProtocolException extends RuntimeException {

	/**
	 * Creates a new exception.
	 * @param message a description of the framing violation
	 */
	public RespProtocolException(String message) {
		super(message);
	}

}
