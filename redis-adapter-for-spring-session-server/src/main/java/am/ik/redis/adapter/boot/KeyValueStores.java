package am.ik.redis.adapter.boot;

import java.util.List;

import am.ik.redis.adapter.store.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The backends of the databases the server offers, in the order {@code SELECT} numbers
 * them.
 *
 * <p>
 * They are held together rather than as one bean each because their number is a
 * configuration property, and because their order <em>is</em> their meaning: the store at
 * index 1 is database 1, and nothing else identifies it.
 *
 * <p>
 * Closing this closes every backend, which is how the container stops whatever they run
 * in the background when the application shuts down.
 *
 * @param databases the backend of each database, at least one
 */
public record KeyValueStores(List<KeyValueStore> databases) implements AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(KeyValueStores.class);

	public KeyValueStores {
		if (databases.isEmpty()) {
			throw new IllegalArgumentException("at least one database is required");
		}
		databases = List.copyOf(databases);
	}

	/**
	 * Returns the backend of one database.
	 * @param index the database number, counting from {@code 0}
	 * @return that database's store
	 * @throws IndexOutOfBoundsException if no such database is served
	 */
	public KeyValueStore database(int index) {
		return this.databases.get(index);
	}

	/**
	 * Closes every backend. One that fails to close does not keep the others open, since
	 * this runs while the application is going away and each of them may hold a thread.
	 */
	@Override
	public void close() {
		for (KeyValueStore store : this.databases) {
			try {
				store.close();
			}
			catch (RuntimeException e) {
				logger.warn("Failed to close a backend", e);
			}
		}
	}

}
