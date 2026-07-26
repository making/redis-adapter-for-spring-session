package am.ik.redis.adapter.dynamodb;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.SetValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second-opinion emulator, opt-in by endpoint override: running the same semantics
 * against a second, independent implementation catches the places where Floci is the only
 * thing that agrees with us.
 *
 * <p>
 * The decided second opinion is kumo (`ghcr.io/sivchari/kumo`) — never the primary,
 * because {@code .todo/024-dynamodb-backend.md} records three checks it is missing (the
 * 400 KB ceiling, the refusal of two operations on one item in a transaction, reserved
 * words), each permissive in the direction that lets a wrong backend pass. Start one and
 * point this suite at it:
 *
 * <pre>{@code
 * docker run --rm -p 8000:8000 ghcr.io/sivchari/kumo
 * ./mvnw test -pl redis-adapter-for-spring-session-dynamodb \
 *     -Dtest=SecondOpinionEmulatorTests \
 *     -Ddynamodb.second-opinion.endpoint=http://localhost:8000
 * }</pre>
 */
@EnabledIfSystemProperty(named = "dynamodb.second-opinion.endpoint", matches = ".+",
		disabledReason = "needs a second emulator: -Ddynamodb.second-opinion.endpoint=http://localhost:8000")
class SecondOpinionEmulatorTests {

	private static DynamoDbClient client;

	private static DynamoDbKeyValueStore store;

	private static RecordingListener listener;

	@BeforeAll
	static void connect() {
		client = DynamoDbClient.builder()
			.endpointOverride(URI.create(System.getProperty("dynamodb.second-opinion.endpoint")))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("second", "opinion")))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
		store = DynamoDbKeyValueStore.builder()
			.client(client)
			.tableName("second-opinion")
			.pollInterval(Duration.ofMillis(50))
			.cursorLag(Duration.ofMillis(200))
			.sweepInterval(Duration.ofMillis(200))
			.build();
		listener = new RecordingListener();
		store.addKeyEventListener(listener);
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
	void theTransactionalCoreHolds() {
		assertThat(store.hset(b("session"), Map.of(b("user"), b("alice")))).isEqualTo(1);
		assertThat(store.sadd(b("bucket"), List.of(b("a"), b("b")))).isEqualTo(2);
		assertThat(store.sadd(b("bucket"), List.of(b("b"), b("c")))).isEqualTo(1);
		SetValue bucket = (SetValue) store.get(b("bucket"));
		assertThat(bucket).isNotNull();
		assertThat(bucket.members()).containsExactlyInAnyOrder(ByteArrayKey.of(b("a")), ByteArrayKey.of(b("b")),
				ByteArrayKey.of(b("c")));
	}

	@Test
	void removalAndAnnouncementTravelTogether() {
		store.hset(b("announced"), Map.of(b("f"), b("v")));
		assertThat(store.delete(b("announced"))).isTrue();
		listener.awaitEvent("deleted announced");
	}

	@Test
	void theSweeperAnnouncesAnUntouchedKeyHereToo() {
		store.hset(b("abandoned-2nd"), Map.of(b("f"), b("v")));
		store.expireAt(b("abandoned-2nd"), store.currentTimeMillis() + 300);
		listener.awaitEvent("expired abandoned-2nd");
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
