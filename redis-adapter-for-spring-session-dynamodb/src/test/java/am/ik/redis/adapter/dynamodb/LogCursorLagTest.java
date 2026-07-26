package am.ik.redis.adapter.dynamodb;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The clock hazard of {@code .docs/design/architecture.md} §12.5: a log entry stamped in
 * the past — a skewed replica's, or a slow write's — must still be delivered, which is
 * the whole reason the cursor lags wall-clock instead of chasing it.
 *
 * <p>
 * A second store with its clock held back stands in for the skewed replica: its removal
 * writes a log entry stamped behind this store's idea of now. With the lag wider than the
 * skew the entry is delivered; a cursor pinned to wall-clock would have passed it and
 * never looked back.
 */
class LogCursorLagTest {

	private static final Duration LAG = Duration.ofMillis(800);

	private static final long SKEW_MILLIS = 400;

	@Test
	void anEntryStampedBehindNowByASkewedReplicaIsStillDelivered() {
		String tableName = "log-cursor-lag";
		RecordingListener listener = new RecordingListener();
		try (DynamoDbKeyValueStore here = DynamoDbKeyValueStore.builder()
			.client(FlociDynamoDb.client())
			.tableName(tableName)
			.pollInterval(Duration.ofMillis(50))
			.cursorLag(LAG)
			.sweeperEnabled(false)
			.build();
				DynamoDbKeyValueStore skewed = DynamoDbKeyValueStore.builder()
					.client(FlociDynamoDb.client())
					.tableName(tableName)
					.pollInterval(Duration.ofMillis(50))
					.cursorLag(LAG)
					.sweeperEnabled(false)
					.clock(() -> System.currentTimeMillis() - SKEW_MILLIS)
					.build()) {
			here.addKeyEventListener(listener);
			here.hset(b("shared"), Map.of(b("f"), b("v")));

			skewed.delete(b("shared"));

			listener.awaitEvent("deleted shared");
		}
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
