package am.ik.redis.adapter.boot.inmemory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the backend this server keeps its sessions in.
 *
 * <p>
 * It is the whole of what a backend module wires up: the properties it is tuned with, and
 * the one factory bean the server asks for a store per database. Everything else — the
 * port, the lifecycle, the actuator — comes from the server module's auto-configuration
 * and is the same whichever backend is underneath.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InMemoryBackendProperties.class)
public class InMemoryBackendConfiguration {

	/**
	 * Registers the in-memory backend.
	 * @param properties how the in-memory backend expires keys nobody touches
	 * @return the factory of this server's backend
	 */
	@Bean
	public InMemoryKeyValueStoreFactory inMemoryKeyValueStoreFactory(InMemoryBackendProperties properties) {
		return new InMemoryKeyValueStoreFactory(properties);
	}

}
