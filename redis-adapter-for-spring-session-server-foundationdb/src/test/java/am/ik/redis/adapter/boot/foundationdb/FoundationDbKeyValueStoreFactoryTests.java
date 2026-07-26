package am.ik.redis.adapter.boot.foundationdb;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import am.ik.redis.adapter.foundationdb.FdbCluster;
import am.ik.redis.adapter.foundationdb.FoundationDbKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the FoundationDB backend contributes to the server.
 *
 * <p>
 * The end-to-end behaviour is covered against a real cluster by
 * {@link FoundationDbBackendEndToEndTests}. What is worth asserting here is the part a
 * deployment gets wrong: that the properties refuse nonsense by naming the property, that
 * two databases really are two keyspaces, and that a factory the server has not yet asked
 * for a store holds nothing.
 */
class FoundationDbKeyValueStoreFactoryTests {

	private static final Duration SHORT = Duration.ofMillis(250);

	@Test
	void answersToTheNameTheServerLogs() {
		assertThat(new FoundationDbKeyValueStoreFactory(properties(null, null)).name()).isEqualTo("foundationdb");
	}

	/**
	 * The factory is built while the application is still starting and is asked for its
	 * stores afterwards, so one that connected as it was built would turn a FoundationDB
	 * that is briefly unreachable into an application that never comes up. Nothing here
	 * even looks at the cluster file.
	 */
	@Test
	void opensNothingUntilItIsAskedForAStore() {
		assertThatCode(() -> new FoundationDbKeyValueStoreFactory(properties("/no/such/fdb.cluster", null)))
			.doesNotThrowAnyException();
	}

	/**
	 * A database is an independent keyspace, so two stores from one factory must share no
	 * keys at all — otherwise {@code SELECT 1} would hand an application somebody else's
	 * sessions.
	 */
	@Test
	void eachDatabaseIsAKeyspaceOfItsOwn() {
		FoundationDbKeyValueStoreFactory factory = new FoundationDbKeyValueStoreFactory(
				properties(FdbCluster.clusterFile(), null, "/factory-" + UUID.randomUUID() + "/"));

		try (KeyValueStore first = factory.create(0); KeyValueStore second = factory.create(1)) {
			byte[] key = "session".getBytes(StandardCharsets.UTF_8);
			first.hset(key, Map.of("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8)));

			assertThat(second.exists(key)).isFalse();
			assertThat(first.exists(key)).isTrue();
			assertThat(((FoundationDbKeyValueStore) second).databaseIndex()).isEqualTo(1);
		}
	}

	/**
	 * A platform that delivers configuration rather than files hands over the cluster
	 * file's contents, and the server writes one. It has to be a real, readable file the
	 * client can also rewrite when the coordinators move.
	 */
	@Test
	void clusterFileContentsAreWrittenToAFileTheClientCanRead() throws Exception {
		String contents = Files.readString(Path.of(FdbCluster.clusterFile()), StandardCharsets.UTF_8).strip();
		FoundationDbKeyValueStoreFactory factory = new FoundationDbKeyValueStoreFactory(
				properties(null, contents, "/contents-" + UUID.randomUUID() + "/"));

		try (KeyValueStore store = factory.create(0)) {
			byte[] key = "written".getBytes(StandardCharsets.UTF_8);
			store.sadd(key, List.of("m".getBytes(StandardCharsets.UTF_8)));

			assertThat(store.exists(key)).isTrue();
		}
	}

	@Test
	void namingBothAClusterFileAndItsContentsIsRefused() {
		assertThatThrownBy(() -> properties("/etc/foundationdb/fdb.cluster", "redis:adapter@fdb-0:4500"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.foundationdb.cluster-file");
	}

	@Test
	void anEmptyKeyPrefixIsRefused() {
		assertThatThrownBy(() -> properties(null, null, "")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.foundationdb.key-prefix");
	}

	@Test
	void aTimeoutThatIsNotPositiveIsRefused() {
		assertThatThrownBy(() -> new FoundationDbBackendProperties(null, null, 730, "/redis-adapter/", Duration.ZERO,
				SHORT, SHORT, Duration.ofSeconds(60), SHORT, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.foundationdb.transaction-timeout");
	}

	/**
	 * A retention shorter than a watch's life would trim the log from under a replica
	 * that is only between watches, which is a lost session event — so the pair is
	 * refused as the properties bind rather than discovered as a hole in the log.
	 */
	@Test
	void aRetentionInsideTheWatchLifetimeIsRefused() {
		assertThatThrownBy(() -> new FoundationDbBackendProperties(null, null, 730, "/redis-adapter/", SHORT,
				Duration.ofSeconds(30), SHORT, Duration.ofSeconds(10), SHORT, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.foundationdb.log-retention");
	}

	@Test
	void anApiVersionThatIsNotOneIsRefused() {
		assertThatThrownBy(() -> new FoundationDbBackendProperties(null, null, 7, "/redis-adapter/", SHORT, SHORT,
				SHORT, Duration.ofSeconds(60), SHORT, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.foundationdb.api-version");
	}

	/**
	 * A cluster file the server cannot read is a deployment's mistake rather than an
	 * outage, and the driver's own message does not even name the file — so the store
	 * says which one it was, as it is created.
	 */
	@Test
	void aClusterFileThatCannotBeReadNamesItself(@TempDir Path directory) {
		String missing = directory.resolve("absent.cluster").toString();
		FoundationDbKeyValueStoreFactory factory = new FoundationDbKeyValueStoreFactory(properties(missing, null));

		assertThatThrownBy(() -> factory.create(0)).hasMessageContaining(missing);
	}

	private static FoundationDbBackendProperties properties(@Nullable String clusterFile,
			@Nullable String clusterFileContents) {
		return properties(clusterFile, clusterFileContents, "/redis-adapter/");
	}

	private static FoundationDbBackendProperties properties(@Nullable String clusterFile,
			@Nullable String clusterFileContents, String keyPrefix) {
		return new FoundationDbBackendProperties(clusterFile, clusterFileContents, 730, keyPrefix,
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60),
				Duration.ofSeconds(1), 10);
	}

}
