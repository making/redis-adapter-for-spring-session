package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Boots the server exactly as it is shipped — the same auto-configuration the runnable
 * application is made of — and points {@code spring.data.redis.*} at it.
 *
 * <p>
 * A test that imports this gets the full stack an application sees in production —
 * Lettuce over TCP, the RESP codec, the command layer and a real backend — with nothing
 * mocked and nothing wired by hand for the occasion. The port comes from
 * {@code application.properties} in the test resources; everything else is the shipped
 * default.
 *
 * <p>
 * What this deliberately does not bring is the backend, since the server has none: a test
 * imports the backend configuration of the module it belongs to beside this one, which is
 * what lets the same harness be run against every backend, this project's and anybody
 * else's.
 *
 * <p>
 * The {@link KeyValueStores} bean is exposed by that configuration, so a test can assert
 * on what actually reached the backend, which is how the key format itself is verified
 * rather than assumed. The names to assert on come from {@link SessionKeys}.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(RedisAdapterServerAutoConfiguration.class)
public class AdapterServerTestConfiguration {

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
