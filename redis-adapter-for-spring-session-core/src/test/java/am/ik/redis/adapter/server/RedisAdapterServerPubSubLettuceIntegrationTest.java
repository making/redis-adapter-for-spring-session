package am.ik.redis.adapter.server;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the pub/sub surface with a real Redis client, over two connections — because the
 * subscriber and the publisher are always different connections, which is the whole
 * reason the registry is shared server-wide.
 *
 * <p>
 * The backend is the fake store with a hand-driven clock, so an expiry happens exactly
 * when the test says it does rather than after a sleep.
 */
class RedisAdapterServerPubSubLettuceIntegrationTest {

	private static final String SHADOW_KEY = "spring:session:sessions:expires:abc";

	private static final String CREATED_PATTERN = "spring:session:event:0:created:*";

	private static final String CREATED_CHANNEL = "spring:session:event:0:created:abc";

	private final FakeKeyValueStore store = new FakeKeyValueStore();

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.store(this.store)
		.build();

	private final RedisClient client = RedisClient.create();

	private final RecordingListener listener = new RecordingListener();

	@BeforeEach
	void startServer() {
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		this.server.stop();
	}

	@Test
	void aPatternSubscriberReceivesAMessagePublishedOnAnotherConnection() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection();
				StatefulRedisConnection<String, String> publisher = connection()) {
			subscriber.sync().psubscribe(CREATED_PATTERN);

			Long receivers = publisher.sync().publish(CREATED_CHANNEL, "delta");

			assertThat(receivers).isEqualTo(1L);
			assertThat(this.listener.take())
				.isEqualTo("pmessage " + CREATED_PATTERN + " " + CREATED_CHANNEL + " delta");
		}
	}

	@Test
	void aPublicationNoPatternMatchesReachesNobody() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection();
				StatefulRedisConnection<String, String> publisher = connection()) {
			subscriber.sync().psubscribe(CREATED_PATTERN);

			Long receivers = publisher.sync().publish("spring:session:event:0:other:abc", "delta");

			assertThat(receivers).isEqualTo(0L);
		}
	}

	@Test
	void deletingAKeyIsPublishedAsADelKeyevent() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection();
				StatefulRedisConnection<String, String> publisher = connection()) {
			subscriber.sync().subscribe("__keyevent@0__:del");
			publisher.sync().append(SHADOW_KEY, "");

			assertThat(publisher.sync().del(SHADOW_KEY)).isEqualTo(1L);

			assertThat(this.listener.take()).isEqualTo("message __keyevent@0__:del " + SHADOW_KEY);
		}
	}

	/**
	 * This is the path Spring Session's background cleanup takes: it touches the shadow
	 * key with {@code EXISTS} to force the lazy expiry, and expects the notification to
	 * come out of that touch.
	 */
	@Test
	void aKeyExpiringWhenItIsTouchedIsPublishedAsAnExpiredKeyevent() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection();
				StatefulRedisConnection<String, String> publisher = connection()) {
			subscriber.sync().subscribe("__keyevent@0__:expired");
			publisher.sync().append(SHADOW_KEY, "");
			publisher.sync().pexpireat(SHADOW_KEY, this.store.currentTimeMillis() + 1000);
			this.store.advance(1001);

			assertThat(publisher.sync().exists(SHADOW_KEY)).isEqualTo(0L);

			assertThat(this.listener.take()).isEqualTo("message __keyevent@0__:expired " + SHADOW_KEY);
		}
	}

	@Test
	void aSubscribedConnectionRefusesADataCommand() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection()) {
			subscriber.sync().subscribe("__keyevent@0__:del");

			assertThatThrownBy(() -> subscriber.sync().exists(SHADOW_KEY))
				.isInstanceOf(RedisCommandExecutionException.class)
				.hasMessage("ERR Can't execute 'exists': only (P|S)SUBSCRIBE / (P|S)UNSUBSCRIBE / PING / QUIT / RESET "
						+ "are allowed in this context");
		}
	}

	@Test
	void aSubscribedConnectionStillAnswersPing() throws Exception {
		try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection()) {
			subscriber.sync().subscribe("__keyevent@0__:del");

			assertThat(subscriber.sync().ping()).isEqualTo("PONG");
		}
	}

	/**
	 * A connection that goes away must leave nothing behind in the registry, so what the
	 * next publication reports is the count of the connections that are still there.
	 */
	@Test
	void aSubscriberThatDisconnectsStopsBeingCounted() throws Exception {
		try (StatefulRedisConnection<String, String> publisher = connection()) {
			try (StatefulRedisPubSubConnection<String, String> subscriber = subscriberConnection()) {
				subscriber.sync().subscribe(CREATED_CHANNEL);
				assertThat(publisher.sync().publish(CREATED_CHANNEL, "delta")).isEqualTo(1L);
			}

			assertThat(awaitNoSubscriber(publisher)).isZero();
		}
	}

	/**
	 * Closing a connection is asynchronous, so the count is polled rather than read once.
	 * @return the receiver count once it reaches zero, or the last count seen
	 */
	private long awaitNoSubscriber(StatefulRedisConnection<String, String> publisher) throws InterruptedException {
		long receivers = -1;
		for (int attempt = 0; attempt < 100; attempt++) {
			receivers = publisher.sync().publish(CREATED_CHANNEL, "delta");
			if (receivers == 0) {
				return receivers;
			}
			Thread.sleep(50);
		}
		return receivers;
	}

	private StatefulRedisPubSubConnection<String, String> subscriberConnection() {
		StatefulRedisPubSubConnection<String, String> connection = this.client.connectPubSub(StringCodec.UTF8,
				redisUri());
		connection.addListener(this.listener);
		return connection;
	}

	private StatefulRedisConnection<String, String> connection() {
		return this.client.connect(StringCodec.UTF8, redisUri());
	}

	private RedisURI redisUri() {
		return RedisURI.builder()
			.withHost("127.0.0.1")
			.withPort(this.server.port())
			.withTimeout(Duration.ofSeconds(10))
			.build();
	}

	/**
	 * Records the deliveries the client hands to the application, so a test asserts on
	 * what a Spring Session listener would actually have seen.
	 */
	private static final class RecordingListener extends RedisPubSubAdapter<String, String> {

		private final BlockingQueue<String> deliveries = new LinkedBlockingQueue<>();

		@Override
		public void message(String channel, String message) {
			this.deliveries.add("message " + channel + " " + message);
		}

		@Override
		public void message(String pattern, String channel, String message) {
			this.deliveries.add("pmessage " + pattern + " " + channel + " " + message);
		}

		String take() throws InterruptedException {
			String delivery = this.deliveries.poll(10, TimeUnit.SECONDS);
			return (delivery == null) ? "<none>" : delivery;
		}

	}

}
