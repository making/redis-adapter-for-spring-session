package am.ik.redis.adapter.pubsub;

/**
 * Something a published message can be delivered to — in practice one client connection
 * that has subscribed.
 *
 * <p>
 * Keeping delivery behind this interface is what lets the {@link PubSubRegistry} route
 * messages without knowing anything about sockets or RESP: the server hands it a
 * {@link RespSubscriber} that writes push frames, and a test hands it something that
 * records what arrived.
 *
 * <h2>Contract</h2> Deliveries happen <strong>synchronously on the publishing
 * thread</strong>, which is the thread running {@code PUBLISH} or the one removing a key.
 * An implementation must therefore be quick, must be safe to call from any thread, and
 * must not throw for an ordinary failure such as a socket that has died — the registry
 * isolates a throwing subscriber so it cannot stop the others from receiving, but a
 * subscriber that blocks holds up the command that published.
 *
 * <p>
 * The arrays passed in belong to the caller and may be shared with other subscribers, so
 * an implementation must treat them as read-only.
 */
public interface Subscriber {

	/**
	 * Delivers a message published to a channel this subscriber subscribed to by name.
	 * @param channel the channel the message was published to (read-only)
	 * @param body the message payload, which is opaque bytes (read-only)
	 */
	void message(byte[] channel, byte[] body);

	/**
	 * Delivers a message published to a channel matching a pattern this subscriber
	 * subscribed to.
	 * @param pattern the pattern that matched (read-only)
	 * @param channel the concrete channel the message was published to (read-only)
	 * @param body the message payload, which is opaque bytes (read-only)
	 */
	void patternMessage(byte[] pattern, byte[] channel, byte[] body);

}
