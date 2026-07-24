package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the backend the server stores session data in.
 *
 * <p>
 * The default is the bundled in-memory reference backend, which is single-node by nature
 * and therefore suits development, single-instance and test deployments. The rest of the
 * server only ever sees the {@link KeyValueStore} SPI, so pointing it at a different
 * backend is a matter of replacing this one bean definition with another module's
 * implementation.
 *
 * <p>
 * The bean type implements {@link AutoCloseable}, so the container stops the backend's
 * active-expiry sweeper when the application context shuts down.
 */
@Configuration(proxyBeanMethods = false)
public class KeyValueStoreConfiguration {

	/**
	 * Creates the default in-memory backend.
	 * @return the key-value store backing this server
	 */
	@Bean
	public KeyValueStore keyValueStore() {
		return InMemoryKeyValueStore.create();
	}

}
