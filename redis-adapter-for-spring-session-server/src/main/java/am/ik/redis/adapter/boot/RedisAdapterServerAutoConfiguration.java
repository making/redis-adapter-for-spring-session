package am.ik.redis.adapter.boot;

import java.util.List;
import java.util.stream.IntStream;

import javax.net.ServerSocketFactory;

import am.ik.redis.adapter.server.RedisAdapterServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;

/**
 * Everything the adapter server is except the backend it stores sessions in.
 *
 * <p>
 * Adding this module to an application is what brings the server: the port, the
 * lifecycle, the actuator, and one backend per configured database. What is deliberately
 * not here is which backend that is. The rest of the server only ever sees the
 * {@link am.ik.redis.adapter.store.KeyValueStore} SPI, so a backend is chosen by putting
 * the server module built around it on the class path — one {@link KeyValueStoreFactory}
 * bean, and nothing here has to know what it stores sessions in.
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
@AutoConfiguration
@EnableConfigurationProperties(RedisAdapterProperties.class)
public class RedisAdapterServerAutoConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(RedisAdapterServerAutoConfiguration.class);

	/**
	 * Creates one backend per configured database, from the one backend this server was
	 * built around.
	 *
	 * <p>
	 * Exactly one is required, and saying so is the whole of the backend selection: the
	 * jar an operator started is the choice, so there is no property to get wrong and no
	 * condition for an ahead-of-time image to settle in advance.
	 * @param backends the backend modules on the class path, of which there must be one
	 * @param properties how many databases to serve
	 * @return the backends, indexed by database number
	 * @throws IllegalStateException if no backend is on the class path, or if more than
	 * one is
	 */
	@Bean
	public KeyValueStores keyValueStores(ObjectProvider<KeyValueStoreFactory> backends,
			RedisAdapterProperties properties) {
		List<KeyValueStoreFactory> registered = backends.orderedStream().toList();
		if (registered.isEmpty()) {
			throw new IllegalStateException("This server has no backend to keep sessions in; add one of the "
					+ "redis-adapter-for-spring-session-server-<backend> modules, or contribute a "
					+ KeyValueStoreFactory.class.getName() + " bean of your own");
		}
		if (registered.size() > 1) {
			// Picking one of them would leave nobody able to say where the sessions went.
			throw new IllegalStateException("This server has more than one backend to keep sessions in: "
					+ registered.stream().map(KeyValueStoreFactory::name).sorted().toList()
					+ "; a server is built around exactly one");
		}
		KeyValueStoreFactory factory = registered.get(0);
		logger.info("Keeping sessions in the {} backend", factory.name());
		return new KeyValueStores(IntStream.range(0, properties.databases()).mapToObj(factory::create).toList());
	}

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
