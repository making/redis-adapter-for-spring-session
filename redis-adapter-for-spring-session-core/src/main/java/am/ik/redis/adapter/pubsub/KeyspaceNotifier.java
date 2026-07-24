package am.ik.redis.adapter.pubsub;

import java.nio.charset.StandardCharsets;

import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;

/**
 * Turns a backend's key removals into Redis keyspace notifications.
 *
 * <p>
 * Redis publishes {@code __keyevent@<db>__:del} when a key is deleted and
 * {@code __keyevent@<db>__:expired} when one dies of its TTL, with the key's own name as
 * the message body. Spring Session's indexed mode subscribes to exactly those two
 * channels and builds its {@code SessionDeletedEvent} and {@code SessionExpiredEvent} out
 * of them — it is never told about a dead session any other way — so a backend that
 * merely dropped expired keys would leave those events unfired.
 *
 * <p>
 * Register one notifier per database on that database's {@link KeyValueStore}: the
 * database index is part of the channel name, and the store itself knows nothing about
 * database numbers.
 *
 * <pre>{@code
 * store.addKeyEventListener(new KeyspaceNotifier(registry, 0));
 * }</pre>
 *
 * <p>
 * The store calls this at the moment of removal, from whichever thread removed the key —
 * the connection thread that ran {@code DEL} or touched an expired key, or the backend's
 * own active-expiry sweeper — and {@link PubSubRegistry#publish} delivers on that same
 * thread, so a subscriber learns of the removal as it happens.
 */
public final class KeyspaceNotifier implements KeyEventListener {

	private final PubSubRegistry registry;

	private final byte[] deletedChannel;

	private final byte[] expiredChannel;

	/**
	 * Creates a notifier publishing the events of one database.
	 * @param registry the server-wide registry to publish through
	 * @param databaseIndex the index of the database whose keys are being watched, which
	 * becomes part of the channel names
	 */
	public KeyspaceNotifier(PubSubRegistry registry, int databaseIndex) {
		this.registry = registry;
		this.deletedChannel = channel(databaseIndex, "del");
		this.expiredChannel = channel(databaseIndex, "expired");
	}

	@Override
	public void onExpired(byte[] key) {
		this.registry.publish(this.expiredChannel, key);
	}

	@Override
	public void onDeleted(byte[] key) {
		this.registry.publish(this.deletedChannel, key);
	}

	private static byte[] channel(int databaseIndex, String event) {
		return ("__keyevent@" + databaseIndex + "__:" + event).getBytes(StandardCharsets.UTF_8);
	}

}
