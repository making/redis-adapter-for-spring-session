package am.ik.redis.adapter.command;

/**
 * Signals that a command was rejected and the client must be told so with a RESP error
 * reply.
 *
 * <p>
 * The {@linkplain #errorMessage() error message} is the text that goes on the wire, so it
 * follows the Redis convention of a leading uppercase error code ({@code ERR},
 * {@code WRONGTYPE}, {@code NOPROTO}, …) and must contain neither CR nor LF. The
 * connection survives: only the current command fails.
 */
public final class RedisCommandException extends RuntimeException {

	private final String errorMessage;

	/**
	 * Creates a new exception.
	 * @param errorMessage the RESP error text, starting with an uppercase error code
	 */
	public RedisCommandException(String errorMessage) {
		super(errorMessage);
		this.errorMessage = errorMessage;
	}

	/**
	 * Returns the RESP error text to send to the client.
	 * @return the error message
	 */
	public String errorMessage() {
		return this.errorMessage;
	}

	/**
	 * Creates the standard Redis reply for a command invoked with the wrong number of
	 * arguments.
	 * @param command the command name in lowercase, as Redis spells it in this message
	 * (for a subcommand, {@code parent|sub})
	 * @return the exception to throw
	 */
	public static RedisCommandException wrongNumberOfArguments(String command) {
		return new RedisCommandException("ERR wrong number of arguments for '" + command + "' command");
	}

	/**
	 * Creates the standard Redis reply for an argument that should have been an integer.
	 * @return the exception to throw
	 */
	public static RedisCommandException notAnInteger() {
		return new RedisCommandException("ERR value is not an integer or out of range");
	}

	/**
	 * Creates the standard Redis reply for an argument that should have been a sorted-set
	 * score.
	 * @return the exception to throw
	 */
	public static RedisCommandException notAValidFloat() {
		return new RedisCommandException("ERR value is not a valid float");
	}

}
