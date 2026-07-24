package am.ik.redis.adapter.protocol;

/**
 * The negotiated RESP protocol version of a connection.
 *
 * <p>
 * A connection starts in {@link #RESP2} and upgrades to {@link #RESP3} after a successful
 * {@code HELLO 3}. The version selects the encoding of nulls, maps, pushes, doubles and
 * booleans in {@link RespWriter}.
 */
public enum RespVersion {

	/** RESP2: the default. Null is {@code $-1}, maps are flat arrays, no push type. */
	RESP2(2),

	/**
	 * RESP3: negotiated via {@code HELLO 3}. Adds {@code _} null, {@code %} map,
	 * {@code >} push.
	 */
	RESP3(3);

	private final int number;

	RespVersion(int number) {
		this.number = number;
	}

	/**
	 * Returns the numeric protocol version (2 or 3) as sent in {@code HELLO}.
	 * @return the protocol number
	 */
	public int number() {
		return this.number;
	}

	/**
	 * Resolves a {@code HELLO} protocol number to a version.
	 * @param number the requested protocol number
	 * @return the matching version
	 * @throws IllegalArgumentException if {@code number} is not 2 or 3; the command layer
	 * is expected to catch this and reply with a {@code NOPROTO} error rather than close
	 * the connection
	 */
	public static RespVersion fromNumber(int number) {
		return switch (number) {
			case 2 -> RESP2;
			case 3 -> RESP3;
			default -> throw new IllegalArgumentException("Unsupported RESP protocol version: " + number);
		};
	}

}
