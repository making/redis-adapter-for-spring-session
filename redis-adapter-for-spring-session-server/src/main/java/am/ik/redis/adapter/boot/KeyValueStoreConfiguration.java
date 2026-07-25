package am.ik.redis.adapter.boot;

import java.util.List;
import java.util.stream.IntStream;

import am.ik.redis.adapter.store.KeyValueStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the backend the server stores session data in, one store per database.
 *
 * <p>
 * Two are bundled. The default is the in-memory reference backend, which is single-node
 * by nature and therefore suits development, single-instance and test deployments; the
 * other is etcd, which several adapters can share and which is what a horizontally scaled
 * deployment needs. The rest of the server only ever sees the {@link KeyValueStore} SPI,
 * so a third backend slots in by contributing its own {@link KeyValueStoreFactory} bean
 * under its own name — nothing here has to know that the other backend exists.
 *
 * <p>
 * Which of the registered backends is used is decided when the application starts, by
 * matching {@code redis-adapter.backend} against the names they give. Deciding it that
 * way rather than by a condition on the bean is what keeps the property meaningful in an
 * ahead-of-time compiled image, where conditions are evaluated once, while the image is
 * built, and an operator's setting would otherwise be quietly ignored.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ RedisAdapterProperties.class, InMemoryBackendProperties.class,
		EtcdBackendProperties.class })
public class KeyValueStoreConfiguration {

	/**
	 * Registers the bundled in-memory backend. It is registered whether or not it is the
	 * one selected, and holds nothing until it is asked for a store.
	 * @param properties how the in-memory backend expires keys nobody touches
	 * @return the factory of the bundled backend
	 */
	@Bean
	public InMemoryKeyValueStoreFactory inMemoryKeyValueStoreFactory(InMemoryBackendProperties properties) {
		return new InMemoryKeyValueStoreFactory(properties);
	}

	/**
	 * Registers the etcd backend, the one several adapters can share. Like the in-memory
	 * one it is registered whether or not it is selected, and connects to nothing until
	 * it is asked for a store.
	 * @param properties where etcd is and how to talk to it
	 * @param sslBundles the bundles a TLS-protected etcd is reached with, which need not
	 * exist when no bundle is named
	 * @return the factory of the etcd backend
	 */
	@Bean
	public EtcdKeyValueStoreFactory etcdKeyValueStoreFactory(EtcdBackendProperties properties,
			ObjectProvider<SslBundles> sslBundles) {
		return new EtcdKeyValueStoreFactory(properties, sslBundles);
	}

	/**
	 * Creates one backend per configured database, from the registered backend whose name
	 * was asked for.
	 * @param backends every backend on the classpath
	 * @param properties which backend to use and how many databases to serve
	 * @return the backends, indexed by database number
	 * @throws IllegalStateException if no registered backend answers to the configured
	 * name, or if more than one does
	 */
	@Bean
	public KeyValueStores keyValueStores(ObjectProvider<KeyValueStoreFactory> backends,
			RedisAdapterProperties properties) {
		List<KeyValueStoreFactory> registered = backends.orderedStream().toList();
		List<KeyValueStoreFactory> selected = registered.stream()
			.filter(backend -> backend.name().equals(properties.backend()))
			.toList();
		if (selected.isEmpty()) {
			throw new IllegalStateException("No backend answers to redis-adapter.backend=" + properties.backend()
					+ "; this server has " + names(registered));
		}
		if (selected.size() > 1) {
			// Picking one of them would leave nobody able to say where the sessions went.
			throw new IllegalStateException(
					"More than one backend answers to redis-adapter.backend=" + properties.backend());
		}
		KeyValueStoreFactory factory = selected.get(0);
		return new KeyValueStores(IntStream.range(0, properties.databases()).mapToObj(factory::create).toList());
	}

	private static List<String> names(List<KeyValueStoreFactory> backends) {
		return backends.stream().map(KeyValueStoreFactory::name).sorted().toList();
	}

}
