package am.ik.redis.adapter.dynamodb;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import am.ik.redis.adapter.store.KeyEventListener;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the line of {@code .docs/design/architecture.md} §12.3: DynamoDB's own TTL is a
 * storage backstop and <strong>never</strong> what fires {@code onExpired}.
 *
 * <p>
 * This test exists because the environment it runs in actively hides the bug it guards
 * against. AWS collects a TTL-expired item best-effort within 48 hours; Floci collects
 * about a second after the TTL attribute's time. A backend that let the emulator's prompt
 * reaper announce expiry would look correct in every local run and leak every abandoned
 * session in production. So: with the sweeper off and the key untouched, nothing may
 * announce the key and the item must still be there well past its deadline — the backstop
 * is written with a margin precisely so no reaper beats the sweeper — and turning a
 * sweeper on is what announces it.
 */
class TtlBackstopIsNotTheExpiryTest {

	@Test
	void onlyTheSweeperAnnouncesAnUntouchedExpiredKey() throws Exception {
		String tableName = "ttl-backstop-line";
		DynamoDbClient client = FlociDynamoDb.client();
		RecordingListener listener = new RecordingListener();
		try (DynamoDbKeyValueStore quiet = DynamoDbKeyValueStore.builder()
			.client(client)
			.tableName(tableName)
			.sweeperEnabled(false)
			.pollInterval(Duration.ofMillis(50))
			.cursorLag(Duration.ofMillis(200))
			.build()) {
			quiet.addKeyEventListener(listener);
			quiet.hset(key(), Map.of(b("f"), b("v")));
			quiet.expireAt(key(), quiet.currentTimeMillis() + 300);

			// Well past the deadline: no sweeper runs, the key is not touched, and the
			// emulator's TTL reaper must not have collected it (the backstop carries a
			// margin) — so nothing has anything to announce.
			listener.assertSilence(Duration.ofSeconds(3));
			assertThat(rawMetaItem(client, tableName)).as("the item, past its deadline, with no sweeper").isNotEmpty();

			// A sweeper is what announces it: same table, same database, sweeper on.
			try (DynamoDbKeyValueStore sweeping = DynamoDbKeyValueStore.builder()
				.client(client)
				.tableName(tableName)
				.sweepInterval(Duration.ofMillis(200))
				.pollInterval(Duration.ofMillis(50))
				.cursorLag(Duration.ofMillis(200))
				.build()) {
				listener.awaitEvent("expired abandoned");
				assertThat(sweeping.exists(key())).isFalse();
			}
		}
	}

	private static byte[] key() {
		return b("abandoned");
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

	private static Map<String, AttributeValue> rawMetaItem(DynamoDbClient client, String tableName) {
		String pk = "k/0/" + Base64.getUrlEncoder().withoutPadding().encodeToString(key());
		return client
			.getItem(r -> r.tableName(tableName)
				.key(Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS("@")))
				.consistentRead(true))
			.item();
	}

}
