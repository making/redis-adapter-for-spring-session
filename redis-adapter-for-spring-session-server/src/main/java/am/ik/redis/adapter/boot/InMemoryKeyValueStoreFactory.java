package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;

/**
 * The bundled backend: an independent map per database, in this process.
 *
 * <p>
 * It is also the worked example of what a backend module contributes — a factory that
 * names itself and hands out one store per database, holding nothing until it is asked
 * to.
 *
 * @param properties how this backend expires keys nobody touches
 */
public record InMemoryKeyValueStoreFactory(InMemoryBackendProperties properties) implements KeyValueStoreFactory {

	@Override
	public String name() {
		return RedisAdapterProperties.IN_MEMORY_BACKEND;
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return InMemoryKeyValueStore.builder()
			.sweeperEnabled(this.properties.sweeperEnabled())
			.sweepInterval(this.properties.sweepInterval())
			.build();
	}

}
