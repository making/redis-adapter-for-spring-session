package am.ik.redis.adapter.boot.etcd;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the etcd backend is pointed at and tuned with. It is read by this server and no
 * other: a server built around another backend brings its own properties, under its own
 * {@code redis-adapter.<backend>} prefix, and has never heard of these.
 *
 * <p>
 * The only setting a deployment has to give is where etcd is. The rest exists because a
 * store on the other side of a network can be slow, protected, or somewhere else in the
 * keyspace of a cluster that is also used for other things.
 *
 * <p>
 * The backend speaks to etcd over the HTTP gateway etcd serves on its client port, which
 * is enabled by default ({@code --enable-grpc-gateway}); a cluster that has turned it off
 * is unreachable for this backend.
 *
 * @param endpoints the client URLs of the etcd cluster. Requests go to the one that last
 * worked and move on to the next when a member cannot be reached
 * @param keyPrefix where in etcd's keyspace the sessions live. Each database gets its own
 * keyspace underneath it ({@code <key-prefix><database>/}), so two adapters that must not
 * share sessions are separated by giving them different prefixes
 * @param connectTimeout how long to wait for a connection to an endpoint
 * @param requestTimeout how long to wait for etcd to answer. It bounds how long a Redis
 * command can hang: a client waiting on a session is better told that something failed
 * @param watchRetryDelay how long to wait before opening the watch again after it fails.
 * The watch is what delivers session events, and while it is down expiries are not
 * announced
 * @param username the etcd user, for a cluster with authentication enabled, or
 * {@code null} for one without
 * @param password that user's password
 * @param sslBundle the name of the {@code spring.ssl.bundle.*} to reach an
 * {@code https://} endpoint with, which is also where a client certificate for mutual TLS
 * comes from. Unset uses the JDK's default trust material
 */
@ConfigurationProperties(prefix = "redis-adapter.etcd")
public record EtcdBackendProperties(@DefaultValue("http://localhost:2379") List<String> endpoints,
		@DefaultValue("/redis-adapter/") String keyPrefix, @DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("5s") Duration requestTimeout, @DefaultValue("1s") Duration watchRetryDelay,
		@Nullable String username, @Nullable String password, @Nullable String sslBundle) {

	public EtcdBackendProperties {
		if (endpoints.isEmpty()) {
			throw new IllegalArgumentException("redis-adapter.etcd.endpoints must name at least one etcd endpoint");
		}
		if (keyPrefix.isBlank()) {
			throw new IllegalArgumentException("redis-adapter.etcd.key-prefix must not be blank");
		}
		// A prefix that does not end in a separator would make the keyspace of database 1
		// the start of database 11's, and one adapter would see the other's sessions.
		keyPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
		requirePositive("connect-timeout", connectTimeout);
		requirePositive("request-timeout", requestTimeout);
		requirePositive("watch-retry-delay", watchRetryDelay);
	}

	private static void requirePositive(String name, Duration value) {
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException("redis-adapter.etcd." + name + " must be positive: " + value);
		}
	}

	/**
	 * Returns the keyspace one database's keys live in, which is what makes the databases
	 * independent of each other in a shared cluster.
	 * @param databaseIndex the database number, counting from {@code 0}
	 * @return the prefix every key of that database carries
	 */
	public String keyPrefix(int databaseIndex) {
		return this.keyPrefix + databaseIndex + "/";
	}

}
