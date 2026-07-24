package am.ik.redis.adapter.store;

/**
 * Callback for key removal events fired by a {@link KeyValueStore}.
 *
 * <p>
 * The store invokes these methods at the moment a key is removed, distinguishing the
 * cause: an explicit delete versus a TTL expiry. The command/pub-sub layer turns them
 * into {@code __keyevent@<db>__:del} and {@code __keyevent@<db>__:expired} keyspace
 * notifications, which is how Spring Session's {@code SessionDeletedEvent} and
 * {@code SessionExpiredEvent} are driven. Because those events depend on the callbacks,
 * they are fired synchronously at removal (never dropped silently).
 *
 * <p>
 * The store passes a fresh copy of the key bytes to each listener, but implementations
 * must still treat the argument as read-only. Callbacks should be fast and must not
 * assume they run under any store lock (they do not) or in any particular order relative
 * to the map state; they may throw, and the store isolates and logs such failures rather
 * than letting one listener break another.
 */
public interface KeyEventListener {

	/**
	 * Called when a key is removed because its TTL elapsed (observed either passively on
	 * access or actively by the background sweeper).
	 * @param key a fresh copy of the removed key's bytes (read-only)
	 */
	default void onExpired(byte[] key) {
	}

	/**
	 * Called when an existing key is removed by an explicit delete.
	 * @param key a fresh copy of the removed key's bytes (read-only)
	 */
	default void onDeleted(byte[] key) {
	}

}
