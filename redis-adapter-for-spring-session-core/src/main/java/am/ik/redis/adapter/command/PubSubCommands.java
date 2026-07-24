package am.ik.redis.adapter.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.pubsub.PubSubRegistry;
import am.ik.redis.adapter.pubsub.Subscriber;
import org.jspecify.annotations.Nullable;

/**
 * The pub/sub commands: {@code SUBSCRIBE}, {@code UNSUBSCRIBE}, {@code PSUBSCRIBE},
 * {@code PUNSUBSCRIBE} and {@code PUBLISH}.
 *
 * <p>
 * Indexed mode rides on all five. Spring Session's listener container subscribes one
 * connection to the two keyspace channels ({@code __keyevent@<db>__:del} and
 * {@code :expired}) and to the created-event pattern
 * ({@code spring:session:event:<db>:created:*}), and publishes a created-event from an
 * ordinary connection whenever a session is saved for the first time. The adapter
 * synthesizes the two keyspace channels itself (see
 * {@code am.ik.redis.adapter.pubsub.KeyspaceNotifier}); the created-event it only routes.
 *
 * <p>
 * Every subscription command answers one confirmation frame per channel or pattern,
 * carrying the running number of subscriptions the connection holds — the same shape as
 * the messages that follow, which is why clients read both off one stream.
 */
public final class PubSubCommands {

	private static final byte[] SUBSCRIBE = "subscribe".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] UNSUBSCRIBE = "unsubscribe".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] PSUBSCRIBE = "psubscribe".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] PUNSUBSCRIBE = "punsubscribe".getBytes(StandardCharsets.US_ASCII);

	private PubSubCommands() {
	}

	/**
	 * Registers every pub/sub command on a dispatcher. All but {@code PUBLISH} stay
	 * available once the connection has subscribed, because they are how it subscribes
	 * further or gets back out again.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("PUBLISH", PubSubCommands::publish)
			.register("SUBSCRIBE", (context, argv) -> subscribe(context, argv, false),
					CommandAvailability.WHILE_SUBSCRIBED)
			.register("PSUBSCRIBE", (context, argv) -> subscribe(context, argv, true),
					CommandAvailability.WHILE_SUBSCRIBED)
			.register("UNSUBSCRIBE", (context, argv) -> unsubscribe(context, argv, false),
					CommandAvailability.WHILE_SUBSCRIBED)
			.register("PUNSUBSCRIBE", (context, argv) -> unsubscribe(context, argv, true),
					CommandAvailability.WHILE_SUBSCRIBED);
	}

	/**
	 * {@code PUBLISH channel message}: delivers the message to every connection
	 * subscribed to the channel by name or by a matching pattern, and replies with how
	 * many that was. Publishing to a channel nobody listens on is normal and replies
	 * {@code 0}.
	 */
	private static void publish(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 3) {
			throw RedisCommandException.wrongNumberOfArguments("publish");
		}
		context.writer().writeInteger(context.pubSub().publish(argv.get(1), argv.get(2)));
	}

	/**
	 * {@code SUBSCRIBE channel [channel ...]} and
	 * {@code PSUBSCRIBE pattern [pattern ...]}: subscribes to each in turn and confirms
	 * each one separately, so that a client learns the running count as it grows.
	 * @param pattern whether the arguments are glob patterns rather than channel names
	 */
	private static void subscribe(CommandContext context, List<byte[]> argv, boolean pattern) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments(pattern ? "psubscribe" : "subscribe");
		}
		PubSubRegistry registry = context.pubSub();
		Subscriber subscriber = context.subscriber();
		for (byte[] topic : argv.subList(1, argv.size())) {
			int count = pattern ? registry.psubscribe(subscriber, topic) : registry.subscribe(subscriber, topic);
			confirm(context.writer(), pattern ? PSUBSCRIBE : SUBSCRIBE, topic, count);
		}
	}

	/**
	 * {@code UNSUBSCRIBE [channel ...]} and {@code PUNSUBSCRIBE [pattern ...]}: gives up
	 * the named subscriptions, or all of them of that kind when none is named.
	 *
	 * <p>
	 * A connection that had nothing to give up is still answered, with a single
	 * confirmation carrying no name at all — clients wait for one frame per request and
	 * would otherwise hang.
	 * @param pattern whether the arguments are glob patterns rather than channel names
	 */
	private static void unsubscribe(CommandContext context, List<byte[]> argv, boolean pattern) throws IOException {
		PubSubRegistry registry = context.pubSub();
		Subscriber subscriber = context.subscriber();
		byte[] kind = pattern ? PUNSUBSCRIBE : UNSUBSCRIBE;
		List<byte[]> topics = (argv.size() > 1) ? argv.subList(1, argv.size())
				: (pattern ? registry.patternsOf(subscriber) : registry.channelsOf(subscriber));
		if (topics.isEmpty()) {
			confirm(context.writer(), kind, null, registry.subscriptionCount(subscriber));
			return;
		}
		for (byte[] topic : topics) {
			int count = pattern ? registry.punsubscribe(subscriber, topic) : registry.unsubscribe(subscriber, topic);
			confirm(context.writer(), kind, topic, count);
		}
	}

	/**
	 * Writes one subscription confirmation: the kind of change, what it was about, and
	 * how many subscriptions the connection holds afterwards. It is framed as a push,
	 * which in RESP2 is an ordinary array — the framing Redis uses in each protocol.
	 * @param topic the channel or pattern, or {@code null} when there was none
	 */
	private static void confirm(RespWriter writer, byte[] kind, byte @Nullable [] topic, int count) throws IOException {
		writer.writePushHeader(3);
		writer.writeBulk(kind);
		writer.writeBulk(topic);
		writer.writeInteger(count);
	}

}
