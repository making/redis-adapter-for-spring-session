package am.ik.redis.adapter.boot;

import java.time.Duration;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.server.RedisAdapterServer;
import am.ik.redis.adapter.store.KeyValueStore;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.session.FindByIndexNameSessionRepository;
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

	/**
	 * Prefix of the shadow key whose death is what Spring Session's indexed mode turns
	 * into a session-deleted or session-expired event.
	 */
	public static final String SHADOW_KEY_PREFIX = SESSION_KEY_PREFIX + "expires:";

	/** Prefix of the per-minute set of sessions due to expire, keyed by epoch millis. */
	public static final String EXPIRATIONS_KEY_PREFIX = "spring:session:expirations:";

	/** Prefix of the set of session ids belonging to one principal. */
	public static final String PRINCIPAL_INDEX_KEY_PREFIX = "spring:session:index:"
			+ FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME + ":";

	/**
	 * Property turning the backend's active-expiry sweeper off, so that a test can prove
	 * a key dies of the access that touches it rather than of a background sweep.
	 */
	public static final String ACTIVE_EXPIRY_PROPERTY = "adapter.test.active-expiry";

	@Bean
	InMemoryKeyValueStore keyValueStore(Environment environment) {
		return InMemoryKeyValueStore.builder()
			.sweepInterval(Duration.ofMillis(50))
			.sweeperEnabled(environment.getProperty(ACTIVE_EXPIRY_PROPERTY, boolean.class, true))
			.build();
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
