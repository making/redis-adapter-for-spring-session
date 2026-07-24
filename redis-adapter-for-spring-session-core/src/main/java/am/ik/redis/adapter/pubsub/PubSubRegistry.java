package am.ik.redis.adapter.pubsub;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.redis.adapter.store.ByteArrayKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who is subscribed to what, for the whole server.
 *
 * <p>
 * The registry has to be shared rather than kept per connection, because a subscriber and
 * a publisher are never the same connection: Spring Session subscribes on the connection
 * its listener container owns and publishes its created-events from an ordinary one, and
 * the {@code del} / {@code expired} keyspace notifications are published by whatever
 * thread happened to remove the key (see {@link KeyspaceNotifier}).
 *
 * <p>
 * A subscription is either to an exact channel name or to a glob {@link GlobPattern
 * pattern}; a publication goes to both, and the receiver count Redis replies with is how
 * many subscriptions it reached.
 *
 * <h2>Thread-safety</h2> Publishing may happen on any thread at any time and is safe
 * against concurrent subscription changes. The subscriptions of one subscriber, on the
 * other hand, are only ever changed by that connection's own thread — a connection sends
 * its {@code SUBSCRIBE} commands one at a time and removes itself when it closes — so the
 * per-subscriber bookkeeping is confined to that thread and keeps subscription order for
 * the confirmations the client is sent.
 */
public final class PubSubRegistry {

	private static final Logger logger = LoggerFactory.getLogger(PubSubRegistry.class);

	private final Map<ByteArrayKey, Set<Subscriber>> channels = new ConcurrentHashMap<>();

	private final Map<ByteArrayKey, Set<Subscriber>> patterns = new ConcurrentHashMap<>();

	private final Map<Subscriber, Subscriptions> subscriptions = new ConcurrentHashMap<>();

	/**
	 * Subscribes to a channel by name.
	 * @param subscriber the connection subscribing
	 * @param channel the exact channel name
	 * @return how many channels and patterns the connection is now subscribed to
	 */
	public int subscribe(Subscriber subscriber, byte[] channel) {
		Subscriptions held = this.subscriptions.computeIfAbsent(subscriber, ignored -> new Subscriptions());
		if (held.channels().add(ByteArrayKey.of(channel))) {
			add(this.channels, channel, subscriber);
		}
		return held.size();
	}

	/**
	 * Subscribes to every channel matching a pattern.
	 * @param subscriber the connection subscribing
	 * @param pattern the glob pattern
	 * @return how many channels and patterns the connection is now subscribed to
	 */
	public int psubscribe(Subscriber subscriber, byte[] pattern) {
		Subscriptions held = this.subscriptions.computeIfAbsent(subscriber, ignored -> new Subscriptions());
		if (held.patterns().add(ByteArrayKey.of(pattern))) {
			add(this.patterns, pattern, subscriber);
		}
		return held.size();
	}

	/**
	 * Unsubscribes from a channel. Unsubscribing from a channel the connection never
	 * subscribed to is a no-op, as it is in Redis.
	 * @param subscriber the connection unsubscribing
	 * @param channel the exact channel name
	 * @return how many channels and patterns the connection is still subscribed to
	 */
	public int unsubscribe(Subscriber subscriber, byte[] channel) {
		Subscriptions held = this.subscriptions.get(subscriber);
		if (held == null) {
			return 0;
		}
		ByteArrayKey key = ByteArrayKey.of(channel);
		if (held.channels().remove(key)) {
			discard(this.channels, key, subscriber);
		}
		return forgetIfEmpty(subscriber, held);
	}

	/**
	 * Unsubscribes from a pattern.
	 * @param subscriber the connection unsubscribing
	 * @param pattern the glob pattern
	 * @return how many channels and patterns the connection is still subscribed to
	 */
	public int punsubscribe(Subscriber subscriber, byte[] pattern) {
		Subscriptions held = this.subscriptions.get(subscriber);
		if (held == null) {
			return 0;
		}
		ByteArrayKey key = ByteArrayKey.of(pattern);
		if (held.patterns().remove(key)) {
			discard(this.patterns, key, subscriber);
		}
		return forgetIfEmpty(subscriber, held);
	}

	/**
	 * Drops every subscription a connection holds, which is what a closing connection
	 * must do so that no publication ever tries to write to its dead socket again.
	 * @param subscriber the connection that is going away
	 */
	public void unsubscribeAll(Subscriber subscriber) {
		Subscriptions held = this.subscriptions.remove(subscriber);
		if (held == null) {
			return;
		}
		for (ByteArrayKey channel : held.channels()) {
			discard(this.channels, channel, subscriber);
		}
		for (ByteArrayKey pattern : held.patterns()) {
			discard(this.patterns, pattern, subscriber);
		}
	}

	/**
	 * Returns the channels a connection subscribed to, in the order it subscribed to
	 * them.
	 * @param subscriber the connection
	 * @return the channel names, each a fresh copy
	 */
	public List<byte[]> channelsOf(Subscriber subscriber) {
		Subscriptions held = this.subscriptions.get(subscriber);
		return (held == null) ? List.of() : copyOf(held.channels());
	}

	/**
	 * Returns the patterns a connection subscribed to, in the order it subscribed to
	 * them.
	 * @param subscriber the connection
	 * @return the patterns, each a fresh copy
	 */
	public List<byte[]> patternsOf(Subscriber subscriber) {
		Subscriptions held = this.subscriptions.get(subscriber);
		return (held == null) ? List.of() : copyOf(held.patterns());
	}

	/**
	 * Returns how many channels and patterns a connection is subscribed to, which is the
	 * count Redis reports in every subscription confirmation.
	 * @param subscriber the connection
	 * @return the number of subscriptions held
	 */
	public int subscriptionCount(Subscriber subscriber) {
		Subscriptions held = this.subscriptions.get(subscriber);
		return (held == null) ? 0 : held.size();
	}

	/**
	 * Reports whether a connection holds any subscription, which is what puts it into
	 * subscriber mode.
	 * @param subscriber the connection
	 * @return {@code true} if it is subscribed to at least one channel or pattern
	 */
	public boolean isSubscribed(Subscriber subscriber) {
		return subscriptionCount(subscriber) > 0;
	}

	/**
	 * Delivers a message to every subscriber of the channel and to every subscriber whose
	 * pattern matches it.
	 *
	 * <p>
	 * Delivery is synchronous, so by the time this returns every subscriber that is still
	 * connected has been written to. A subscriber that fails is logged and skipped, which
	 * keeps one broken connection from swallowing an event the others are waiting for; it
	 * is still counted, because the count reports who was subscribed rather than whose
	 * socket accepted the bytes.
	 * @param channel the channel to publish to
	 * @param body the message payload, which is opaque bytes
	 * @return how many subscriptions the message reached
	 */
	public int publish(byte[] channel, byte[] body) {
		int receivers = 0;
		Set<Subscriber> byName = this.channels.get(ByteArrayKey.of(channel));
		if (byName != null) {
			for (Subscriber subscriber : byName) {
				deliver(() -> subscriber.message(channel, body));
				receivers++;
			}
		}
		for (Map.Entry<ByteArrayKey, Set<Subscriber>> entry : this.patterns.entrySet()) {
			byte[] pattern = entry.getKey().asBytes();
			if (!GlobPattern.matches(pattern, channel)) {
				continue;
			}
			for (Subscriber subscriber : entry.getValue()) {
				deliver(() -> subscriber.patternMessage(pattern, channel, body));
				receivers++;
			}
		}
		return receivers;
	}

	private static void deliver(Runnable delivery) {
		try {
			delivery.run();
		}
		catch (RuntimeException e) {
			logger.warn("Failed to deliver a message to a subscriber", e);
		}
	}

	/**
	 * Stops tracking a connection that has just given up its last subscription, so that a
	 * connection which never subscribes again leaves nothing behind.
	 * @return the number of subscriptions the connection still holds
	 */
	private int forgetIfEmpty(Subscriber subscriber, Subscriptions held) {
		if (held.size() == 0) {
			this.subscriptions.remove(subscriber);
		}
		return held.size();
	}

	private static void add(Map<ByteArrayKey, Set<Subscriber>> index, byte[] topic, Subscriber subscriber) {
		// compute rather than computeIfAbsent, so that adding a subscriber cannot race
		// with
		// the removal of a set that has just become empty.
		index.compute(ByteArrayKey.of(topic), (ignored, subscribers) -> {
			Set<Subscriber> holders = (subscribers != null) ? subscribers : ConcurrentHashMap.newKeySet();
			holders.add(subscriber);
			return holders;
		});
	}

	private static void discard(Map<ByteArrayKey, Set<Subscriber>> index, ByteArrayKey topic, Subscriber subscriber) {
		index.computeIfPresent(topic, (ignored, subscribers) -> {
			subscribers.remove(subscriber);
			return subscribers.isEmpty() ? null : subscribers;
		});
	}

	private static List<byte[]> copyOf(Set<ByteArrayKey> topics) {
		List<byte[]> copy = new ArrayList<>(topics.size());
		for (ByteArrayKey topic : topics) {
			copy.add(topic.asBytes());
		}
		return copy;
	}

	/**
	 * What one connection is subscribed to. Only that connection's own thread changes it,
	 * so the insertion-ordered sets need no synchronization and keep the order the client
	 * subscribed in.
	 */
	private static final class Subscriptions {

		private final Set<ByteArrayKey> channels = new LinkedHashSet<>();

		private final Set<ByteArrayKey> patterns = new LinkedHashSet<>();

		Set<ByteArrayKey> channels() {
			return this.channels;
		}

		Set<ByteArrayKey> patterns() {
			return this.patterns;
		}

		int size() {
			return this.channels.size() + this.patterns.size();
		}

	}

}
