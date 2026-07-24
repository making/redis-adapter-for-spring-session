package am.ik.redis.adapter.command;

/**
 * Decides which credentials a connection is accepted with.
 *
 * <p>
 * A server is either open, in which case every connection may run commands immediately
 * ({@link #open()}), or it requires credentials, in which case a connection may only run
 * {@code AUTH}, {@code HELLO} and {@code QUIT} until it has sent the right ones; anything
 * else is answered with {@code NOAUTH Authentication required.} Clients send credentials
 * either as {@code AUTH [username] password} or as
 * {@code HELLO <protover> AUTH <username> <password>}, and a client that sends only a
 * password authenticates as {@link #DEFAULT_USERNAME}.
 *
 * <p>
 * Credentials cross the network as clear text, exactly as they do against Redis, so a
 * server that requires them should also be given an {@code SSLServerSocketFactory}.
 *
 * <p>
 * Implementations must be safe for concurrent use: one instance serves every connection.
 * They should also compare secrets in constant time, as {@link #password(String)} does,
 * so that response times reveal nothing about the expected value.
 */
@FunctionalInterface
public interface Authenticator {

	/** The user a client authenticates as when it sends a password on its own. */
	String DEFAULT_USERNAME = "default";

	/**
	 * Checks a client's credentials.
	 * @param username the user name sent, or {@link #DEFAULT_USERNAME} when the client
	 * sent only a password
	 * @param password the password sent
	 * @return whether the connection may run commands
	 */
	boolean authenticate(String username, String password);

	/**
	 * Whether connections must authenticate before they may run commands. When this is
	 * {@code false} a connection starts out authenticated and {@code AUTH} accepts
	 * anything, so a client configured with a password still reaches a server that needs
	 * none.
	 * @return {@code true} unless the server is open
	 */
	default boolean isRequired() {
		return true;
	}

	/**
	 * Returns an authenticator that requires nothing.
	 * @return an open authenticator
	 */
	static Authenticator open() {
		return new OpenAuthenticator();
	}

	/**
	 * Returns an authenticator that accepts {@link #DEFAULT_USERNAME} with the given
	 * password.
	 * @param password the expected password, which must not be empty
	 * @return a password authenticator
	 */
	static Authenticator password(String password) {
		return new PasswordAuthenticator(DEFAULT_USERNAME, password);
	}

	/**
	 * Returns an authenticator that accepts one user name and password pair.
	 * @param username the expected user name, which must not be empty
	 * @param password the expected password, which must not be empty
	 * @return a password authenticator
	 */
	static Authenticator usernamePassword(String username, String password) {
		return new PasswordAuthenticator(username, password);
	}

}
