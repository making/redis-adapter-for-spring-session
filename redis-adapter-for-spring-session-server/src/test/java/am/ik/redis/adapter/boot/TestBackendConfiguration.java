package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.store.KeyValueStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The backend this module's tests serve sessions out of.
 *
 * <p>
 * This module ships no backend — a server is built around one, in a module of its own —
 * so the tests bring one, and it is written the way a backend module writes its own: a
 * single factory bean, naming the backend it is. Everything these tests are actually
 * about is above the SPI, so what is underneath only has to be a real store.
 */
@Configuration(proxyBeanMethods = false)
public class TestBackendConfiguration {

	/**
	 * Property turning the active-expiry sweeper off, so that a test can prove a key dies
	 * of the access that touches it rather than of a background sweep.
	 */
	public static final String ACTIVE_EXPIRY_PROPERTY = "redis-adapter.test-backend.sweeper-enabled";

	@Bean
	KeyValueStoreFactory testKeyValueStoreFactory(
			@Value("${" + ACTIVE_EXPIRY_PROPERTY + ":true}") boolean sweeperEnabled) {
		return new TestBackend(sweeperEnabled);
	}

	/**
	 * The factory of the test backend, handing out a store per database that records what
	 * it was created for and when it was closed.
	 *
	 * @param sweeperEnabled whether the stores it creates sweep expired keys
	 */
	public record TestBackend(boolean sweeperEnabled) implements KeyValueStoreFactory {

		@Override
		public String name() {
			return "test";
		}

		@Override
		public KeyValueStore create(int databaseIndex) {
			return new RecordingKeyValueStore(databaseIndex, this.sweeperEnabled);
		}

	}

}
