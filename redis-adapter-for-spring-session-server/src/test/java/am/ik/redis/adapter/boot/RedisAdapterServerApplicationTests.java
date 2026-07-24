package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the server application context starts and that the bundled in-memory
 * backend from the {@code -inmemory} module is wired as the default
 * {@link KeyValueStore}.
 */
@SpringBootTest
class RedisAdapterServerApplicationTests {

	@Autowired
	KeyValueStore keyValueStore;

	@Test
	void inMemoryBackendIsWiredAsTheDefaultStore() {
		assertThat(this.keyValueStore).isInstanceOf(InMemoryKeyValueStore.class);

		byte[] key = "wiring-probe".getBytes(UTF_8);
		this.keyValueStore.append(key, "v".getBytes(UTF_8));
		assertThat(this.keyValueStore.exists(key)).isTrue();
	}

}
