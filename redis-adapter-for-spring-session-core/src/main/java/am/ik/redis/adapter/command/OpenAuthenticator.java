package am.ik.redis.adapter.command;

/**
 * The authenticator of a server that requires no credentials: connections start
 * authenticated, and credentials sent anyway are accepted rather than rejected.
 */
final class OpenAuthenticator implements Authenticator {

	@Override
	public boolean authenticate(String username, String password) {
		return true;
	}

	@Override
	public boolean isRequired() {
		return false;
	}

	@Override
	public String toString() {
		return "Authenticator.open()";
	}

}
