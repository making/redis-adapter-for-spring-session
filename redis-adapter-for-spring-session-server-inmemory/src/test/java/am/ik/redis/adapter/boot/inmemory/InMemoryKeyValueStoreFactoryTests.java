package am.ik.redis.adapter.boot.inmemory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What this backend contributes to the server.
 *
 * <p>
 * What it does with a session is covered by the compatibility suite, which drives this
 * server through a real client. What is worth asserting on its own is the part the seam
 * promises and nothing else can observe: that each database gets a keyspace of its own,
 * and that the settings an operator wrote reach the store rather than being bound and
 * ignored.
 */
class InMemoryKeyValueStoreFactoryTests {

	private static final InMemoryBackendProperties PROPERTIES = new InMemoryBackendProperties(true,
			Duration.ofSeconds(1));

	@Test
	void answersToTheNameTheServerLogs() {
		assertThat(new InMemoryKeyValueStoreFactory(PROPERTIES).name()).isEqualTo("in-memory");
	}

	@Test
	void givesEachDatabaseAKeyspaceOfItsOwn() {
		InMemoryKeyValueStoreFactory factory = new InMemoryKeyValueStoreFactory(PROPERTIES);

		try (KeyValueStore first = factory.create(0); KeyValueStore second = factory.create(1)) {
			assertThat(first).isInstanceOf(InMemoryKeyValueStore.class).isNotSameAs(second);

			byte[] key = "session".getBytes(StandardCharsets.UTF_8);
			first.append(key, new byte[0]);

			assertThat(first.exists(key)).isTrue();
			assertThat(second.exists(key)).isFalse();
		}
	}

	/**
	 * A sweep interval that is not positive would either spin or never run, and both are
	 * worse than being told the setting is wrong.
	 */
	@Test
	void aSweepIntervalThatIsNotPositiveIsRefused() {
		assertThatThrownBy(() -> new InMemoryBackendProperties(true, Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.in-memory.sweep-interval");
	}

}
