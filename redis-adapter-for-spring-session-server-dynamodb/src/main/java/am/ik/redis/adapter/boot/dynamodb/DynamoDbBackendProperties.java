package am.ik.redis.adapter.boot.dynamodb;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the DynamoDB backend is tuned with. It is read by this server and no other, and it
 * deliberately does not say where DynamoDB <em>is</em>: the client arrives from Spring
 * Cloud AWS, so the region, the credentials and the endpoint override that points a local
 * run at an emulator are ordinary {@code spring.cloud.aws.*} properties.
 *
 * <p>
 * Nothing here is required. The defaults suit a session store; the two an operator should
 * read before scaling are {@code shards}, which is fixed for the life of a table, and
 * {@code poll-interval}, which is a standing charge as well as a latency
 * ({@code .docs/design/architecture.md} §12.5).
 *
 * @param tableName the table the sessions live in. It is created — on-demand billing, the
 * deadline index, the TTL storage backstop — when absent, unless {@code create-table}
 * says otherwise
 * @param createTable whether to create the table when it is absent. Off for a deployment
 * whose tables are provisioned elsewhere; the server then fails on first use if the table
 * is missing, rather than quietly making one
 * @param shards how many partitions a set's members and the deadline index spread over.
 * DynamoDB caps one partition at 1,000 writes per second whatever the table's capacity,
 * and every session expiring in the same minute joins one bucket, so this is that
 * bucket's ceiling in thousands of writes per second. Fixed for the life of a table —
 * members stay where the shard count that wrote them put them
 * @param pollInterval how often the key-event log is polled. Half of how long a session
 * event takes to arrive, and a standing charge: an idle poll is a billed read, every
 * interval, per replica and database
 * @param cursorLag how far the log cursor stays behind wall-clock. An entry stamped
 * behind the cursor is never seen, so this must outlast the fleet's clock skew plus a
 * write's latency; it is the other half of an event's arrival time
 * @param sweepInterval how long between sweeps for keys nobody touches, which is the
 * longest an abandoned session can sit unannounced
 * @param logRetention how long read key-event log entries are kept before the sweeper
 * trims them. It has to comfortably outlast {@code cursor-lag}
 * @param requestTimeout how long to wait for DynamoDB to answer one request, which bounds
 * how long a Redis command can hang
 * @param maxAttempts how many times an operation retries a key that changed underneath
 * it, or a request DynamoDB throttled, before giving up
 */
@ConfigurationProperties(prefix = "redis-adapter.dynamodb")
public record DynamoDbBackendProperties(@DefaultValue("redis-adapter") String tableName,
		@DefaultValue("true") boolean createTable, @DefaultValue("4") int shards,
		@DefaultValue("100ms") Duration pollInterval, @DefaultValue("500ms") Duration cursorLag,
		@DefaultValue("1s") Duration sweepInterval, @DefaultValue("60s") Duration logRetention,
		@DefaultValue("5s") Duration requestTimeout, @DefaultValue("10") int maxAttempts) {

	public DynamoDbBackendProperties {
		if (tableName.isBlank()) {
			throw new IllegalArgumentException("redis-adapter.dynamodb.table-name must not be blank");
		}
		if (shards < 1) {
			throw new IllegalArgumentException("redis-adapter.dynamodb.shards must be at least 1: " + shards);
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException(
					"redis-adapter.dynamodb.max-attempts must be at least 1: " + maxAttempts);
		}
		requirePositive("poll-interval", pollInterval);
		requirePositive("cursor-lag", cursorLag);
		requirePositive("sweep-interval", sweepInterval);
		requirePositive("log-retention", logRetention);
		requirePositive("request-timeout", requestTimeout);
		if (cursorLag.compareTo(logRetention) >= 0) {
			throw new IllegalArgumentException("redis-adapter.dynamodb.log-retention (" + logRetention
					+ ") must comfortably outlast redis-adapter.dynamodb.cursor-lag (" + cursorLag + ")");
		}
	}

	private static void requirePositive(String name, Duration value) {
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException("redis-adapter.dynamodb." + name + " must be positive: " + value);
		}
	}

}
