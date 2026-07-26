package am.ik.redis.adapter.boot.foundationdb;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the FoundationDB backend is tuned with. It is read by this server and no other.
 *
 * <p>
 * Where the cluster is has a shape of its own here, unlike every other backend: a
 * FoundationDB client is not given a URL, it reads a <strong>cluster file</strong> and
 * finds the coordinators from it. So either name the file this deployment already has
 * ({@code cluster-file}), or hand the server its contents ({@code cluster-file-contents})
 * and let it write one — which is what a container platform that delivers configuration
 * as environment variables needs. Setting neither leaves FoundationDB to look where it
 * always looks ({@code FDB_CLUSTER_FILE}, then {@code /etc/foundationdb/fdb.cluster}),
 * which is what an image with the client package installed already has.
 *
 * <p>
 * Nothing here is required. The two an operator should read before scaling are
 * {@code log-retention}, which is a correctness setting rather than a housekeeping one,
 * and {@code transaction-timeout}, which is what bounds a Redis command against a cluster
 * that has gone away.
 *
 * @param clusterFile the path of the cluster file. Mutually exclusive with
 * {@code cluster-file-contents}; setting neither leaves the client to look where it
 * always looks
 * @param clusterFileContents the contents of a cluster file to write and use, for a
 * deployment that delivers configuration rather than files. It looks like
 * {@code <description>:<id>@<host>:<port>,...}
 * @param apiVersion the FoundationDB API version to speak. It may be selected only once
 * per JVM, so every database of one server shares it, and the installed native client has
 * to support it
 * @param keyPrefix where in the cluster's keyspace the sessions live. Each database gets
 * a keyspace of its own underneath it, so two deployments can share a cluster by taking
 * different prefixes
 * @param transactionTimeout how long one transaction may take. It bounds how long a Redis
 * command can hang — a FoundationDB read against a cluster that is not there waits for
 * ever otherwise — and has to stay under FoundationDB's own five-second transaction
 * lifetime to be the thing that fires
 * @param watchTimeout how long one watch on the key-event counter lives before it is
 * renewed. Events ordinarily arrive as soon as the watch fires; this bounds the wait when
 * a watch is lost, and is what keeps a cluster that went away from leaving a store
 * waiting for ever
 * @param sweepInterval how long between sweeps for sessions nobody comes back to, which
 * is the longest an abandoned session can sit unannounced
 * @param logRetention how long key-event log entries are kept before the sweeper trims
 * them. A replica away for longer than this comes back to a log that has moved on without
 * it and loses the events in between, so it is a correctness setting
 * @param retryDelay how long before the follower that delivers session events is started
 * again after it fails
 * @param maxAttempts how many times FoundationDB retries a transaction that conflicted
 * before giving up
 */
@ConfigurationProperties(prefix = "redis-adapter.foundationdb")
public record FoundationDbBackendProperties(@Nullable String clusterFile, @Nullable String clusterFileContents,
		@DefaultValue("730") int apiVersion, @DefaultValue("/redis-adapter/") String keyPrefix,
		@DefaultValue("5s") Duration transactionTimeout, @DefaultValue("5s") Duration watchTimeout,
		@DefaultValue("1s") Duration sweepInterval, @DefaultValue("60s") Duration logRetention,
		@DefaultValue("1s") Duration retryDelay, @DefaultValue("10") int maxAttempts) {

	public FoundationDbBackendProperties {
		if (clusterFile != null && clusterFileContents != null) {
			throw new IllegalArgumentException(
					"set redis-adapter.foundationdb.cluster-file or redis-adapter.foundationdb.cluster-file-contents, "
							+ "not both: one names a file and the other is one");
		}
		if (clusterFile != null && clusterFile.isBlank()) {
			throw new IllegalArgumentException("redis-adapter.foundationdb.cluster-file must not be blank");
		}
		if (clusterFileContents != null && clusterFileContents.isBlank()) {
			throw new IllegalArgumentException("redis-adapter.foundationdb.cluster-file-contents must not be blank");
		}
		if (keyPrefix.isEmpty()) {
			throw new IllegalArgumentException("redis-adapter.foundationdb.key-prefix must not be empty");
		}
		if (apiVersion < 200) {
			throw new IllegalArgumentException(
					"redis-adapter.foundationdb.api-version must be a FoundationDB API version such as 730: "
							+ apiVersion);
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException(
					"redis-adapter.foundationdb.max-attempts must be at least 1: " + maxAttempts);
		}
		requirePositive("transaction-timeout", transactionTimeout);
		requirePositive("watch-timeout", watchTimeout);
		requirePositive("sweep-interval", sweepInterval);
		requirePositive("log-retention", logRetention);
		requirePositive("retry-delay", retryDelay);
		if (logRetention.compareTo(watchTimeout) <= 0) {
			throw new IllegalArgumentException("redis-adapter.foundationdb.log-retention (" + logRetention
					+ ") must comfortably outlast redis-adapter.foundationdb.watch-timeout (" + watchTimeout
					+ "), or the log is trimmed from under a replica that is only between watches");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException("redis-adapter.foundationdb." + name + " must be positive: " + value);
		}
	}

}
