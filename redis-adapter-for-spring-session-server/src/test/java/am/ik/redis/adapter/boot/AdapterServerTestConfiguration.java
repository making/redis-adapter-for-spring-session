package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Boots the server exactly as it is shipped — the same configuration classes the runnable
 * application is made of — and points {@code spring.data.redis.*} at it.
 *
 * <p>
 * A test that imports this gets the full stack an application sees in production —
 * Lettuce over TCP, the RESP codec, the command layer and a real backend — with nothing
 * mocked and nothing wired by hand for the occasion. The port and the sweep interval come
 * from {@code application.properties} in this module's test resources; everything else is
 * the shipped default.
 *
 * <p>
 * The {@link KeyValueStores} bean is exposed by that configuration, so a test can assert
 * on what actually reached the backend, which is how the key format itself is verified
 * rather than assumed.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({ KeyValueStoreConfiguration.class, RedisAdapterServerConfiguration.class })
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
	 * Property turning the in-memory backend's active-expiry sweeper off, so that a test
	 * can prove a key dies of the access that touches it rather than of a background
	 * sweep.
	 */
	public static final String ACTIVE_EXPIRY_PROPERTY = "redis-adapter.in-memory.sweeper-enabled";

	/**
	 * Starts the server and publishes the port it bound. The server binds its socket as
	 * it is created, before anything that reads these properties exists, so the client is
	 * configured with the real port rather than a guess.
	 *
	 * <p>
	 * Accepting is started here rather than left to {@link RedisAdapterServerLifecycle
	 * the lifecycle bean}, which is deliberate and is the one way these tests differ from
	 * a deployment. A deployed adapter is a process of its own that applications connect
	 * to long after it is up, so it starts serving once its own context is ready. Here
	 * the adapter and the application share a context, and Spring Session's indexed mode
	 * asks Redis about {@code notify-keyspace-events} while that context is still being
	 * built — against a server that had only bound its port, that call would wait for a
	 * context that is waiting for it.
	 * @param redisAdapterServer the bound server
	 * @return the registrar that points the Redis client at the adapter
	 */
	@Bean
	DynamicPropertyRegistrar redisAdapterConnectionProperties(RedisAdapterServer redisAdapterServer) {
		redisAdapterServer.start();
		return registry -> {
			registry.add("spring.data.redis.host", () -> "127.0.0.1");
			registry.add("spring.data.redis.port", redisAdapterServer::port);
		};
	}

}
