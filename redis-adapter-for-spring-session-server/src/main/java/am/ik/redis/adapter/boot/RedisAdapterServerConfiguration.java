package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the adapter server to its configuration, its lifecycle and the actuator.
 *
 * <p>
 * The server is bound as its bean is created and starts accepting through
 * {@link RedisAdapterServerLifecycle}. Binding early is what makes a port that is already
 * taken stop the application there and then, rather than leaving a process running that
 * serves nobody, and it is what lets the rest of the application ask which port the
 * server ended up on when it was given an ephemeral one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisAdapterProperties.class)
public class RedisAdapterServerConfiguration {

	/**
	 * Creates the server and binds its port.
	 * @param properties where to listen, how to authenticate clients, how long to let
	 * them finish on shutdown
	 * @param databases the backends of the databases it serves
	 * @return the bound server, not yet accepting connections
	 */
	@Bean(initMethod = "bind")
	public RedisAdapterServer redisAdapterServer(RedisAdapterProperties properties, KeyValueStores databases) {
		if (properties.ssl().enabled() || properties.ssl().bundle() != null) {
			// Serving plain TCP to an operator who asked for TLS is the one failure that
			// is never noticed, so any sign of the request is refused rather than logged.
			throw new IllegalStateException("redis-adapter.ssl is set, but this server cannot serve TLS yet");
		}
		RedisAdapterServer.Builder builder = RedisAdapterServer.builder()
			.host(properties.bindAddress())
			.port(properties.port())
			.shutdownTimeout(properties.shutdownTimeout())
			.databases(databases.databases());
		String password = properties.password();
		if (password != null) {
			builder.password(password);
		}
		return builder.build();
	}

	/**
	 * Starts and stops the server with the application context.
	 * @param redisAdapterServer the bound server
	 * @return the lifecycle of the server
	 */
	@Bean
	public RedisAdapterServerLifecycle redisAdapterServerLifecycle(RedisAdapterServer redisAdapterServer) {
		return new RedisAdapterServerLifecycle(redisAdapterServer);
	}

	/**
	 * Reports the server through {@code /actuator/health}.
	 * @param redisAdapterServer the server to report on
	 * @param databases the backends it serves
	 * @return the health indicator of the server
	 */
	@Bean
	public RedisAdapterServerHealthIndicator redisAdapterServerHealthIndicator(RedisAdapterServer redisAdapterServer,
			KeyValueStores databases) {
		return new RedisAdapterServerHealthIndicator(redisAdapterServer, databases);
	}

	/**
	 * Measures the server through the application's meter registry.
	 * @param redisAdapterServer the server to measure
	 * @return the metrics of the server
	 */
	@Bean
	public RedisAdapterServerMetrics redisAdapterServerMetrics(RedisAdapterServer redisAdapterServer) {
		return new RedisAdapterServerMetrics(redisAdapterServer);
	}

}
