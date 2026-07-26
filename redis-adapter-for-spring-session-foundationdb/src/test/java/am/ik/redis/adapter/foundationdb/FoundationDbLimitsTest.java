package am.ik.redis.adapter.foundationdb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import am.ik.redis.adapter.store.ValueTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two ways this backend fails that an application can do something about, and the one
 * it must never do.
 *
 * <p>
 * FoundationDB's ceilings are hard, exact and non-retryable, so what the client is told
 * matters: {@code ERR internal error} would send whoever is holding the exception looking
 * for a bug in the adapter, when what the application has to do is keep less in the
 * session. And a read against a cluster that is not there waits <strong>for ever</strong>
 * unless the transaction has a deadline — which is the failure this backend is most
 * exposed to, since a Redis command that never answers is worse than one that fails.
 */
class FoundationDbLimitsTest {

	@Test
	void aValueOverTheCeilingIsRefusedAsTooLargeAndNothingIsWritten() {
		String prefix = "/limits-" + UUID.randomUUID() + "/";
		try (FoundationDbKeyValueStore store = open(prefix)) {
			byte[] key = b(prefix + "session");

			assertThatThrownBy(() -> store.hset(key, Map.of(b("attr"), new byte[100_001])))
				.isInstanceOf(ValueTooLargeException.class)
				.hasMessageContaining("100000");

			assertThat(store.exists(key)).isFalse();
		}
	}

	/**
	 * Exactly at the ceiling is accepted; it is the next byte that is not. A limit
	 * documented as 100,000 and enforced at 65,536 would be a worse surprise than no
	 * documentation at all.
	 */
	@Test
	void aValueExactlyAtTheCeilingIsAccepted() {
		String prefix = "/limits-edge-" + UUID.randomUUID() + "/";
		try (FoundationDbKeyValueStore store = open(prefix)) {
			byte[] key = b(prefix + "session");

			assertThatCode(() -> store.hset(key, Map.of(b("attr"), new byte[100_000]))).doesNotThrowAnyException();

			assertThat(store.exists(key)).isTrue();
		}
	}

	/**
	 * A Redis key is bytes and may be any length, but a FoundationDB key may not: the
	 * tuple-encoded key of a hash field carries the Redis key and the field name
	 * together, so both of them count towards the same 10,000 bytes.
	 */
	@Test
	void aKeyOverTheCeilingIsRefusedAsTooLarge() {
		String prefix = "/limits-key-" + UUID.randomUUID() + "/";
		try (FoundationDbKeyValueStore store = open(prefix)) {
			byte[] enormous = new byte[10_001];
			java.util.Arrays.fill(enormous, (byte) 'k');

			assertThatThrownBy(() -> store.sadd(enormous, List.of(b("member"))))
				.isInstanceOf(ValueTooLargeException.class)
				.hasMessageContaining("10000");
		}
	}

	/**
	 * The failure this backend would otherwise hang on. A cluster file that names nothing
	 * opens perfectly well — no connection is made — and the first read then waits for
	 * ever unless the transaction carries a deadline. The assertion that matters is that
	 * it comes back <em>at all</em>, and roughly when it said it would.
	 */
	@Test
	void aReadAgainstAClusterThatIsNotThereFailsWhenItsDeadlinePassesRatherThanHanging(@TempDir Path directory)
			throws Exception {
		Path nowhere = directory.resolve("nowhere.cluster");
		Files.writeString(nowhere, "nobody:nobody@127.0.0.1:1\n", StandardCharsets.UTF_8);
		try (FoundationDbKeyValueStore store = FoundationDbKeyValueStore.builder()
			.clusterFile(nowhere.toString())
			.keyPrefix("/limits-away-" + UUID.randomUUID() + "/")
			.transactionTimeout(Duration.ofMillis(500))
			.sweeperEnabled(false)
			.build()) {
			long began = System.nanoTime();

			assertThatThrownBy(() -> store.exists(b("anything"))).isInstanceOf(FoundationDbException.class);

			assertThat(Duration.ofNanos(System.nanoTime() - began)).isLessThan(Duration.ofSeconds(10));
		}
	}

	/**
	 * A cluster file that cannot be read is a deployment's mistake rather than an outage,
	 * so unlike an unreachable cluster it fails as the store is built and says which file
	 * it was.
	 */
	@Test
	void aClusterFileThatIsNotThereFailsAsTheStoreIsBuilt(@TempDir Path directory) {
		String missing = directory.resolve("absent.cluster").toString();

		assertThatThrownBy(() -> FoundationDbKeyValueStore.builder().clusterFile(missing).build())
			.isInstanceOf(FoundationDbException.class)
			.hasMessageContaining(missing);
	}

	private static FoundationDbKeyValueStore open(String prefix) {
		return FoundationDbKeyValueStore.builder()
			.clusterFile(FdbCluster.clusterFile())
			.keyPrefix(prefix)
			.sweeperEnabled(false)
			.build();
	}

	private static byte[] b(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

}
