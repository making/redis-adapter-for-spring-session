package am.ik.redis.adapter.boot;

import java.time.Duration;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.server.RedisAdapterServer;
import am.ik.redis.adapter.store.KeyValueStore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Boots an adapter server on an ephemeral port, backed by the in-memory backend, and
 * points {@code spring.data.redis.*} at it.
 *
 * <p>
 * A test that imports this gets the full stack an application sees in production —
 * Lettuce over TCP, the RESP codec, the command layer and a real backend — with nothing
 * mocked. The {@link KeyValueStore} bean is exposed so a test can assert on what actually
 * reached the backend, which is how the key format itself is verified rather than
 * assumed.
 *
 * <p>
 * The sweep interval is short so that expiry-driven tests observe an unaccessed key going
 * away without waiting a second for it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AdapterServerTestConfiguration {

	/** Sessions are stored under this prefix by Spring Session's defaults. */
	public static final String SESSION_KEY_PREFIX = "spring:session:sessions:";

	@Bean
	InMemoryKeyValueStore keyValueStore() {
		return InMemoryKeyValueStore.builder().sweepInterval(Duration.ofMillis(50)).build();
	}

	@Bean(initMethod = "start", destroyMethod = "stop")
	RedisAdapterServer redisAdapterServer(KeyValueStore keyValueStore) {
		return RedisAdapterServer.builder().host("127.0.0.1").port(0).store(keyValueStore).build();
	}

	/**
	 * Publishes the port the server actually bound. The server is started by the time
	 * this registrar runs, so the client is configured with the real port rather than a
	 * guess.
	 * @param redisAdapterServer the running server
	 * @return the registrar that points the Redis client at the adapter
	 */
	@Bean
	DynamicPropertyRegistrar redisAdapterConnectionProperties(RedisAdapterServer redisAdapterServer) {
		return registry -> {
			registry.add("spring.data.redis.host", () -> "127.0.0.1");
			registry.add("spring.data.redis.port", redisAdapterServer::port);
		};
	}

}
