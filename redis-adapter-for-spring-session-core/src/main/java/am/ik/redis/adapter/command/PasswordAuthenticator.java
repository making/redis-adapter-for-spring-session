package am.ik.redis.adapter.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * An {@link Authenticator} holding one user name and password pair.
 */
final class PasswordAuthenticator implements Authenticator {

	private final byte[] username;

	private final byte[] password;

	/**
	 * Creates an authenticator.
	 * @param username the expected user name
	 * @param password the expected password
	 */
	PasswordAuthenticator(String username, String password) {
		if (username.isEmpty()) {
			throw new IllegalArgumentException("username must not be empty");
		}
		if (password.isEmpty()) {
			throw new IllegalArgumentException("password must not be empty");
		}
		this.username = username.getBytes(StandardCharsets.UTF_8);
		this.password = password.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public boolean authenticate(String username, String password) {
		// Both halves are always compared, with a comparison that does not stop at the
		// first difference, so how long a rejection takes says nothing about how much of
		// the guess was right.
		boolean matchingUsername = MessageDigest.isEqual(username.getBytes(StandardCharsets.UTF_8), this.username);
		boolean matchingPassword = MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), this.password);
		return matchingUsername & matchingPassword;
	}

	@Override
	public String toString() {
		return "Authenticator.usernamePassword(" + new String(this.username, StandardCharsets.UTF_8) + ", ****)";
	}

}
