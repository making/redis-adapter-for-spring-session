package am.ik.redis.adapter.boot;

import javax.net.ServerSocketFactory;

import am.ik.redis.adapter.server.RedisAdapterServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
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
 *
 * <p>
 * Whether the port is served over TLS is decided here, as the bean is created, rather
 * than by a condition on it. Conditions are evaluated once, while an ahead-of-time
 * compiled image is built, which would leave {@code redis-adapter.ssl} settled by whoever
 * built the image instead of by the operator who deploys it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisAdapterProperties.class)
public class RedisAdapterServerConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(RedisAdapterServerConfiguration.class);

	/**
	 * Creates the server and binds its port.
	 * @param properties where to listen, how to authenticate clients, whether to serve
	 * TLS, how long to let them finish on shutdown
	 * @param databases the backends of the databases it serves
	 * @param sslBundles the certificate material of the application, which
	 * {@code redis-adapter.ssl.bundle} picks from; absent in a context that has no SSL
	 * configuration at all
	 * @return the bound server, not yet accepting connections
	 */
	@Bean(initMethod = "bind")
	public RedisAdapterServer redisAdapterServer(RedisAdapterProperties properties, KeyValueStores databases,
			ObjectProvider<SslBundles> sslBundles) {
		RedisAdapterServer.Builder builder = RedisAdapterServer.builder()
			.host(properties.bindAddress())
			.port(properties.port())
			.shutdownTimeout(properties.shutdownTimeout())
			.serverSocketFactory(serverSocketFactory(properties.ssl(), sslBundles))
			.databases(databases.databases());
		String password = properties.password();
		if (password != null) {
			builder.password(password);
		}
		return builder.build();
	}

	/**
	 * Resolves what the listening socket is created by: the SSL bundle that was named, or
	 * plain TCP when none was.
	 *
	 * <p>
	 * A bundle the application declared {@code reload-on-update} is followed for as long
	 * as the server runs, so that a certificate an issuer renews on disk is served to the
	 * clients that connect after it without anything being restarted.
	 * @param ssl the transport security settings
	 * @param sslBundles the certificate material of the application
	 * @return the factory the server binds its port with
	 * @throws IllegalStateException if TLS was asked for in a context that has no SSL
	 * bundles
	 */
	private static ServerSocketFactory serverSocketFactory(RedisAdapterProperties.Ssl ssl,
			ObjectProvider<SslBundles> sslBundles) {
		String name = ssl.bundle();
		if (!ssl.isEnabled()) {
			if (name != null) {
				logger.warn("redis-adapter.ssl.bundle={} is configured but redis-adapter.ssl.enabled is false; "
						+ "the adapter is serving plain TCP", name);
			}
			return ServerSocketFactory.getDefault();
		}
		// isEnabled() is only ever true with a bundle to serve, since a server enabled
		// without one is refused as the properties bind.
		SslBundles bundles = sslBundles.getIfAvailable();
		if (bundles == null || name == null) {
			throw new IllegalStateException("redis-adapter.ssl asks for TLS, but this application has no SSL bundles; "
					+ "define one under spring.ssl.bundle.*");
		}
		SslBundleServerSocketFactory factory = SslBundleServerSocketFactory.builder()
			.bundleName(name)
			.bundle(bundles.getBundle(name))
			.clientAuth(ssl.clientAuth())
			.build();
		bundles.addBundleUpdateHandler(name, factory::rotate);
		logger.info("Serving TLS from SSL bundle '{}', client authentication {}", name, ssl.clientAuth());
		return factory;
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
