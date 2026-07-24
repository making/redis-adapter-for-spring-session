package am.ik.redis.adapter.server;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the database index down in the keyspace channel names, over the raw protocol.
 *
 * <p>
 * A server that hard-coded {@code __keyevent@0__:} would pass every other test in the
 * suite, because everything else runs on database 0. It would also fail silently in
 * production: no error is raised, the notification simply never reaches the channel the
 * client subscribed to, and the application sees sessions that are never reported as
 * expired. So the assertions here are on the channel <em>name</em> the server publishes
 * on, not merely on an event arriving.
 *
 * <p>
 * The subscriber subscribes to the database 0 channel as well as the database 1 one, so a
 * notification published under the wrong index is caught by the same read rather than by
 * a timeout. The backends are fake stores with hand-driven clocks, so an expiry happens
 * exactly when the test says it does.
 */
class RedisAdapterServerMultiDatabaseKeyspaceTest {

	private static final String SHADOW_KEY = "spring:session:sessions:expires:abc";

	private final FakeKeyValueStore firstDatabase = new FakeKeyValueStore();

	private final FakeKeyValueStore secondDatabase = new FakeKeyValueStore();

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.databases(List.of(this.firstDatabase, this.secondDatabase))
		.build();

	@BeforeEach
	void startServer() {
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	/**
	 * The path Spring Session's background cleanup takes, on a non-default database: it
	 * touches the shadow key with {@code EXISTS} to force the lazy expiry, and expects
	 * the notification to come out of that touch — on the channel of the database its
	 * connection selected.
	 */
	@Test
	void aKeyExpiringOnDatabaseOneIsPublishedOnTheDatabaseOneChannel() throws Exception {
		try (RawRedisClient subscriber = connect(); RawRedisClient writer = connect()) {
			subscribe(subscriber, "__keyevent@0__:expired", "__keyevent@1__:expired");
			selectDatabaseOne(writer);
			writer.send("APPEND", SHADOW_KEY, "");
			assertThat(readLine(writer)).isEqualTo(":0");
			assertThat(this.secondDatabase.exists(SHADOW_KEY.getBytes(UTF_8))).isTrue();
			writer.send("PEXPIREAT", SHADOW_KEY, Long.toString(this.secondDatabase.currentTimeMillis() + 1000));
			assertThat(readLine(writer)).isEqualTo(":1");
			this.secondDatabase.advance(1001);

			writer.send("EXISTS", SHADOW_KEY);
			assertThat(readLine(writer)).isEqualTo(":0");

			assertThat(readMessage(subscriber)).isEqualTo("message __keyevent@1__:expired " + SHADOW_KEY);
			assertNothingElseArrived(subscriber);
		}
		// The SELECT is what put the key in the second store. Had it gone to the first,
		// the channel name would have been right for the wrong reason.
		assertThat(this.firstDatabase.exists(SHADOW_KEY.getBytes(UTF_8))).isFalse();
	}

	@Test
	void deletingAKeyOnDatabaseOneIsPublishedOnTheDatabaseOneChannel() throws Exception {
		try (RawRedisClient subscriber = connect(); RawRedisClient writer = connect()) {
			subscribe(subscriber, "__keyevent@0__:del", "__keyevent@1__:del");
			selectDatabaseOne(writer);
			writer.send("APPEND", SHADOW_KEY, "");
			assertThat(readLine(writer)).isEqualTo(":0");
			assertThat(this.secondDatabase.exists(SHADOW_KEY.getBytes(UTF_8))).isTrue();

			writer.send("DEL", SHADOW_KEY);
			assertThat(readLine(writer)).isEqualTo(":1");

			assertThat(readMessage(subscriber)).isEqualTo("message __keyevent@1__:del " + SHADOW_KEY);
			assertNothingElseArrived(subscriber);
		}
	}

	/**
	 * The default database keeps publishing under {@code @0} once the server serves more
	 * than one, which is what makes the existing single-database tests still meaningful.
	 */
	@Test
	void deletingAKeyOnDatabaseZeroIsPublishedOnTheDatabaseZeroChannel() throws Exception {
		try (RawRedisClient subscriber = connect(); RawRedisClient writer = connect()) {
			subscribe(subscriber, "__keyevent@0__:del", "__keyevent@1__:del");
			writer.send("APPEND", SHADOW_KEY, "");
			assertThat(readLine(writer)).isEqualTo(":0");

			writer.send("DEL", SHADOW_KEY);
			assertThat(readLine(writer)).isEqualTo(":1");

			assertThat(readMessage(subscriber)).isEqualTo("message __keyevent@0__:del " + SHADOW_KEY);
			assertNothingElseArrived(subscriber);
		}
	}

	/**
	 * Pub/sub is server-wide in Redis: a channel is not scoped to a database, and a
	 * subscriber is reached whatever its own connection selected. Asserted explicitly
	 * because the obvious "fix", if a notification ever looked misrouted, is to make the
	 * registry per database — which would be wrong, and would break Spring Session's
	 * listener container, whose connection never issues a {@code SELECT} of its own.
	 */
	@Test
	void aSubscriberOnDatabaseOneStillReceivesTheEventsOfDatabaseZero() throws Exception {
		try (RawRedisClient subscriber = connect(); RawRedisClient writer = connect()) {
			selectDatabaseOne(subscriber);
			subscribe(subscriber, "__keyevent@0__:del");
			writer.send("APPEND", SHADOW_KEY, "");
			assertThat(readLine(writer)).isEqualTo(":0");

			writer.send("DEL", SHADOW_KEY);
			assertThat(readLine(writer)).isEqualTo(":1");

			assertThat(readMessage(subscriber)).isEqualTo("message __keyevent@0__:del " + SHADOW_KEY);
		}
	}

	private RawRedisClient connect() throws IOException {
		return new RawRedisClient(this.server.port());
	}

	private void selectDatabaseOne(RawRedisClient client) throws IOException {
		client.send("SELECT", "1");
		assertThat(readLine(client)).isEqualTo("+OK");
	}

	/**
	 * Subscribes to each channel in turn and reads the confirmation frame each one
	 * produces, so that the next frame the test reads is a delivery.
	 */
	private void subscribe(RawRedisClient client, String... channels) throws IOException {
		String[] request = new String[channels.length + 1];
		request[0] = "SUBSCRIBE";
		System.arraycopy(channels, 0, request, 1, channels.length);
		client.send(request);
		for (int index = 0; index < channels.length; index++) {
			assertThat(readLine(client)).isEqualTo("*3");
			assertThat(readBulk(client)).isEqualTo("subscribe");
			assertThat(readBulk(client)).isEqualTo(channels[index]);
			assertThat(readLine(client)).isEqualTo(":" + (index + 1));
		}
	}

	/**
	 * Reads one delivery and renders it as the client's application would see it.
	 * @return {@code "message <channel> <body>"}
	 */
	private static String readMessage(RawRedisClient client) throws IOException {
		assertThat(readLine(client)).isEqualTo("*3");
		assertThat(readBulk(client)).isEqualTo("message");
		return "message " + readBulk(client) + " " + readBulk(client);
	}

	/**
	 * Asserts that the subscriber has no further delivery waiting, by asking it something
	 * that answers on the same stream. A notification published on the other database's
	 * channel would already be in the socket — it is written before the reply to the
	 * command that removed the key — so it would be read here instead of the
	 * {@code PONG}.
	 */
	private static void assertNothingElseArrived(RawRedisClient subscriber) throws IOException {
		subscriber.send("PING");
		assertThat(readLine(subscriber)).isEqualTo("+PONG");
	}

	private static String readBulk(RawRedisClient client) throws IOException {
		String header = readLine(client);
		assertThat(header).startsWith("$");
		String value = readLine(client);
		assertThat(value).hasSize(Integer.parseInt(header.substring(1)));
		return value;
	}

	private static String readLine(RawRedisClient client) throws IOException {
		return Objects.requireNonNull(client.readLine(), "the server closed the connection");
	}

}
