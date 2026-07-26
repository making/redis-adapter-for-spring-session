package am.ik.redis.adapter.dynamodb;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in suite against a <strong>real</strong> DynamoDB table, covering what the
 * emulator cannot ({@code .docs/design/architecture.md} §12.6): the ordinary build never
 * runs it, because it needs AWS credentials and it costs money.
 *
 * <p>
 * Run it by naming a table and a region — the table is created if absent, on-demand, and
 * the keys carry a run-scoped prefix so a shared table is safe:
 *
 * <pre>{@code
 * ./mvnw test -pl redis-adapter-for-spring-session-dynamodb \
 *     -Dtest=RealDynamoDbTests \
 *     -Ddynamodb.real.table=redis-adapter-real-test \
 *     -Ddynamodb.real.region=ap-northeast-1
 * }</pre>
 *
 * Credentials come from the default provider chain. What only this suite can say: that
 * the semantics hold against AWS's enforcement rather than an emulator's, and — the most
 * dangerous gap — that an overdue item is still physically present long after its
 * deadline (AWS's TTL collects within 48 hours, best effort), so the sweeper really is
 * the only thing announcing expiry. Throttling and the per-partition write ceiling stay
 * uncovered here too: provoking them costs real money at scale, and the retry path is
 * proven against injected failures in {@link ThrottlingRetryTest}.
 */
@EnabledIfSystemProperty(named = "dynamodb.real.table", matches = ".+",
		disabledReason = "needs a real table: -Ddynamodb.real.table=... -Ddynamodb.real.region=...")
class RealDynamoDbTests {

	private static DynamoDbClient client;

	private static DynamoDbKeyValueStore store;

	private static RecordingListener listener;

	private static String prefix;

	@BeforeAll
	static void connect() {
		client = DynamoDbClient.builder()
			.region(Region.of(System.getProperty("dynamodb.real.region", "us-east-1")))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
		store = DynamoDbKeyValueStore.builder()
			.client(client)
			.tableName(System.getProperty("dynamodb.real.table"))
			.build();
		listener = new RecordingListener();
		store.addKeyEventListener(listener);
		prefix = "real-" + UUID.randomUUID() + "/";
	}

	@AfterAll
	static void disconnect() {
		if (store != null) {
			store.close();
		}
		if (client != null) {
			client.close();
		}
	}

	@Test
	void aSessionShapedRoundTripHolds() {
		byte[] session = b(prefix + "session");
		assertThat(store.hset(session, Map.of(b("user"), b("alice")))).isEqualTo(1);
		assertThat(store.expireAt(session, store.currentTimeMillis() + 60_000)).isTrue();
		assertThat(store.exists(session)).isTrue();
		assertThat(store.sadd(b(prefix + "bucket"), List.of(session))).isEqualTo(1);
		assertThat(store.delete(session)).isTrue();
		listener.awaitEvent("deleted " + prefix + "session");
	}

	/**
	 * The gap the emulator hides: on AWS an overdue item is still there — the TTL
	 * backstop has not fired and will not for hours — and it is the sweeper's removal
	 * that announces it.
	 */
	@Test
	void anAbandonedKeyIsAnnouncedByTheSweeperNotByAwsTtl() {
		byte[] abandoned = b(prefix + "abandoned");
		store.hset(abandoned, Map.of(b("f"), b("v")));
		store.expireAt(abandoned, store.currentTimeMillis() + 500);

		listener.awaitEvent("expired " + prefix + "abandoned");
		assertThat(store.exists(abandoned)).isFalse();
	}

	@Test
	void theHealthCheckAnswers() {
		store.checkHealth();
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
