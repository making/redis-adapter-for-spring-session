package am.ik.redis.adapter.boot.inmemory;

import am.ik.redis.adapter.boot.KeyValueStoreFactory;
import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;

/**
 * The in-memory backend: an independent map per database, in this process.
 *
 * <p>
 * It is also the worked example of what a backend module contributes — a factory that
 * names itself and hands out one store per database, holding nothing until it is asked
 * to.
 *
 * @param properties how this backend expires keys nobody touches
 */
public record InMemoryKeyValueStoreFactory(InMemoryBackendProperties properties) implements KeyValueStoreFactory {

	/** The name this backend is known by, in the logs and in the documentation. */
	public static final String NAME = "in-memory";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return InMemoryKeyValueStore.builder()
			.sweeperEnabled(this.properties.sweeperEnabled())
			.sweepInterval(this.properties.sweepInterval())
			.build();
	}

}
