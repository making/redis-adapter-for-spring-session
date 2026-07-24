package am.ik.redis.adapter.command;

/**
 * When a connection is allowed to run a command, beyond the default of "once
 * authenticated, and only outside subscriber mode".
 *
 * <p>
 * These are declared at registration time so that the {@link CommandDispatcher} can
 * refuse a command before it reaches its handler, and no handler has to check for itself.
 */
public enum CommandAvailability {

	/**
	 * The command may run before the connection has authenticated. Reserve this for the
	 * handshake itself: a client cannot authenticate without being allowed to send
	 * {@code AUTH} or {@code HELLO} first, and refusing to let it hang up with
	 * {@code QUIT} would help nobody.
	 */
	UNAUTHENTICATED,

	/**
	 * The command may run while the connection is in subscriber mode. Redis puts a
	 * connection that has subscribed into a mode where only the subscription commands,
	 * {@code PING} and {@code QUIT} are accepted, because that connection's output stream
	 * is carrying pushes; the adapter does the same.
	 */
	WHILE_SUBSCRIBED

}
