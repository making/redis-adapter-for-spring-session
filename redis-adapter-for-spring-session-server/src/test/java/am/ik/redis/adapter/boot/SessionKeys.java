package am.ik.redis.adapter.boot;

import org.springframework.session.FindByIndexNameSessionRepository;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The key and channel names Spring Session builds out of its namespace, so that a test
 * can assert on what actually reached the backend rather than on the round trip alone.
 *
 * <p>
 * All of them move together: an application that sets
 * {@code @EnableRedisIndexedHttpSession(redisNamespace = ...)} shifts every key it writes
 * and the channel it announces new sessions on. Deriving them here from one namespace,
 * rather than spelling the default ones out as constants, is what lets a test on a custom
 * namespace assert exactly what the default-namespace tests assert.
 *
 * @param namespace the namespace as an application writes it, with no trailing colon
 */
record SessionKeys(String namespace) {

	/** The namespace Spring Session uses when an application names none. */
	static final SessionKeys DEFAULT = new SessionKeys("spring:session");

	SessionKeys {
		if (namespace.isBlank() || namespace.endsWith(":")) {
			throw new IllegalArgumentException(
					"the namespace is written as an application writes it, with no trailing colon: " + namespace);
		}
	}

	/**
	 * Returns the key the session hash itself lives under.
	 * @param sessionId the session id
	 * @return the session key
	 */
	byte[] session(String sessionId) {
		return bytes(this.namespace + ":sessions:" + sessionId);
	}

	/**
	 * Returns the key of the shadow entry whose death is what Spring Session's indexed
	 * mode turns into a session-deleted or session-expired event.
	 * @param sessionId the session id
	 * @return the shadow key
	 */
	byte[] shadow(String sessionId) {
		return bytes(this.namespace + ":sessions:expires:" + sessionId);
	}

	/**
	 * Returns the key of the set holding one principal's session ids.
	 * @param principal the principal name
	 * @return the principal index key
	 */
	byte[] principalIndex(String principal) {
		return bytes(this.namespace + ":index:" + FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME + ":"
				+ principal);
	}

	/**
	 * Returns the key of the set of sessions due to expire in one minute, which is what
	 * the cleanup job reads.
	 * @param bucket the start of that minute, in epoch millis
	 * @return the expirations key, as a string because it is written through
	 * {@code RedisOperations} rather than read off the backend
	 */
	String expirations(long bucket) {
		return this.namespace + ":expirations:" + bucket;
	}

	/**
	 * Returns the key of the sorted set the alternative expiration store keeps every live
	 * session in, scored by the moment it is due to expire. It replaces the minute
	 * buckets rather than joining them, and sits under the sessions prefix rather than
	 * beside them.
	 * @return the expirations sorted-set key, as a string because it is queried through
	 * {@code RedisOperations}
	 */
	String expirationsSortedSet() {
		return this.namespace + ":sessions:expirations";
	}

	/**
	 * Returns {@link #expirationsSortedSet()} as the backend sees it.
	 * @return the expirations sorted-set key in bytes
	 */
	byte[] expirationsSortedSetKey() {
		return bytes(expirationsSortedSet());
	}

	/**
	 * Returns the prefix of the channel Spring Session publishes session-created events
	 * on. It carries the database index as well as the namespace, so it is the one name
	 * that moves with either.
	 * @param database the database the application's connection selected
	 * @return the created-channel prefix, the session id being its suffix
	 */
	String createdChannelPrefix(int database) {
		return this.namespace + ":event:" + database + ":created:";
	}

	private static byte[] bytes(String key) {
		return key.getBytes(UTF_8);
	}

}
