package am.ik.redis.adapter.boot.foundationdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import am.ik.redis.adapter.boot.KeyValueStoreFactory;
import am.ik.redis.adapter.foundationdb.FoundationDbKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;

/**
 * The FoundationDB backend: one cluster, one keyspace of it per database, shared by every
 * adapter pointed at it.
 *
 * <p>
 * This is a backend for a horizontally scaled deployment on a store built for exactly
 * that: several adapters serve the same sessions, the sessions outlive every adapter, and
 * a key one adapter removes is announced to the clients connected to all of them. What it
 * has that the other shared backends do not is real multi-key transactions, so a session
 * save is one commit rather than six raft writes or eight billed requests.
 *
 * <p>
 * Nothing is opened until {@link #create(int)} is called — which is what keeps an
 * unreachable cluster a failure of the first session rather than of bean creation. The
 * cluster file is resolved then too, since writing one out of
 * {@code cluster-file-contents} is the one thing this factory ever puts on disk.
 */
public final class FoundationDbKeyValueStoreFactory implements KeyValueStoreFactory {

	/** The name this backend is known by, in the logs and in the documentation. */
	public static final String NAME = "foundationdb";

	private final FoundationDbBackendProperties properties;

	/** Where the cluster file is, once somebody has asked for a store. */
	private volatile @Nullable String clusterFile;

	/**
	 * Creates the factory.
	 * @param properties what the backend is tuned with
	 */
	public FoundationDbKeyValueStoreFactory(FoundationDbBackendProperties properties) {
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	/**
	 * Returns what the backend is tuned with.
	 * @return the properties
	 */
	public FoundationDbBackendProperties properties() {
		return this.properties;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return FoundationDbKeyValueStore.builder()
			.clusterFile(clusterFile())
			.apiVersion(this.properties.apiVersion())
			.keyPrefix(this.properties.keyPrefix())
			.databaseIndex(databaseIndex)
			.transactionTimeout(this.properties.transactionTimeout())
			.watchTimeout(this.properties.watchTimeout())
			.sweepInterval(this.properties.sweepInterval())
			.logRetention(this.properties.logRetention())
			.retryDelay(this.properties.retryDelay())
			.maxAttempts(this.properties.maxAttempts())
			.build();
	}

	/**
	 * Returns the cluster file every database of this server reads, writing one out of
	 * {@code cluster-file-contents} the first time it is asked.
	 *
	 * <p>
	 * The file is written once and shared, rather than once per database: the client
	 * rewrites a cluster file in place when the coordinators change, and several stores
	 * pointed at one file all learn of the move.
	 * @return the path, or {@code null} to leave the client to look where it always looks
	 */
	private synchronized @Nullable String clusterFile() {
		String resolved = this.clusterFile;
		if (resolved != null) {
			return resolved;
		}
		if (this.properties.clusterFile() != null) {
			this.clusterFile = this.properties.clusterFile();
			return this.clusterFile;
		}
		String contents = this.properties.clusterFileContents();
		if (contents == null) {
			return null;
		}
		try {
			Path file = Files.createTempFile("redis-adapter-", ".cluster");
			file.toFile().deleteOnExit();
			// The client rewrites this file when the coordinators change, so it has to be
			// somewhere writable rather than, say, a read-only mounted secret.
			Files.writeString(file, contents.strip() + "\n", StandardCharsets.UTF_8);
			this.clusterFile = file.toString();
			return this.clusterFile;
		}
		catch (IOException e) {
			throw new UncheckedIOException(
					"Could not write the cluster file redis-adapter.foundationdb.cluster-file-contents describes", e);
		}
	}

}
