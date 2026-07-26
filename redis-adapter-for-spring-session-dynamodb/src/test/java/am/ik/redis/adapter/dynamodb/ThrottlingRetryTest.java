package am.ik.redis.adapter.dynamodb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The throttling path, against injected failures — deliberately, because this is one of
 * the four things the emulator cannot prove ({@code .docs/design/architecture.md} §12.6):
 * Floci never throttles, so {@code ProvisionedThroughputExceededException} does not occur
 * in a local run and a retry path proven only by the ordinary suite would be proven by
 * nothing.
 */
class ThrottlingRetryTest {

	@Test
	void aThrottledWriteIsRetriedUntilItLands() {
		AtomicInteger failures = new AtomicInteger(2);
		try (DynamoDbKeyValueStore store = store("throttled-then-lands", throttling(failures))) {
			assertThat(store.sadd(b("s"), List.of(b("a")))).isEqualTo(1);
			assertThat(failures.get()).isZero();
		}
	}

	@Test
	void throttlingThatNeverStopsIsGivenUpOnWithTheBackendsOwnFailure() {
		AtomicInteger failures = new AtomicInteger(Integer.MAX_VALUE);
		try (DynamoDbKeyValueStore store = store("throttled-forever", throttling(failures))) {
			assertThatThrownBy(() -> store.sadd(b("s"), List.of(b("a")))).isInstanceOf(DynamoDbBackendException.class)
				.hasCauseInstanceOf(ProvisionedThroughputExceededException.class);
		}
	}

	private static DynamoDbKeyValueStore store(String tableName, DynamoDbClient client) {
		return DynamoDbKeyValueStore.builder()
			.client(client)
			.tableName(tableName)
			.maxAttempts(3)
			.pollInterval(Duration.ofSeconds(5))
			.sweeperEnabled(false)
			.build();
	}

	/**
	 * Returns a client that throttles {@code TransactWriteItems} while {@code failures}
	 * lasts and passes everything else through to the emulator.
	 * @param failures how many transactions to refuse
	 * @return the flaky client
	 */
	private static DynamoDbClient throttling(AtomicInteger failures) {
		DynamoDbClient real = FlociDynamoDb.client();
		return (DynamoDbClient) Proxy.newProxyInstance(DynamoDbClient.class.getClassLoader(),
				new Class<?>[] { DynamoDbClient.class }, (proxy, method, args) -> {
					if ("transactWriteItems".equals(method.getName()) && failures.get() > 0) {
						failures.decrementAndGet();
						throw ProvisionedThroughputExceededException.builder()
							.message("injected: the table is over its capacity")
							.build();
					}
					try {
						return method.invoke(real, args);
					}
					catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
