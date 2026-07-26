package am.ik.redis.adapter.boot.dynamodb;

import am.ik.redis.adapter.boot.KeyValueStoreFactory;
import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB backend: one table, one keyspace of it per database, shared by every
 * adapter pointed at it.
 *
 * <p>
 * This is a backend for a horizontally scaled deployment with nothing of its own to
 * operate: several adapters serve the same sessions, the sessions outlive every adapter,
 * a key one adapter removes is announced to the clients connected to all of them — and
 * underneath is a table AWS runs, not a cluster anyone patches.
 *
 * <p>
 * The client is a bean Spring Cloud AWS built from the {@code spring.cloud.aws.*}
 * properties; building a client opens no connection, and the factory itself asks nothing
 * of DynamoDB until {@link #create(int)} is called — which is what keeps an unreachable
 * table a failure of the first session rather than of bean creation.
 *
 * @param properties what the backend is tuned with
 * @param client the client every request goes through, signed and pointed the way the
 * deployment configured Spring Cloud AWS
 */
public record DynamoDbKeyValueStoreFactory(DynamoDbBackendProperties properties,
		DynamoDbClient client) implements KeyValueStoreFactory {

	/** The name this backend is known by, in the logs and in the documentation. */
	public static final String NAME = "dynamodb";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public KeyValueStore create(int databaseIndex) {
		return DynamoDbKeyValueStore.builder()
			.client(this.client)
			.tableName(this.properties.tableName())
			.databaseIndex(databaseIndex)
			.shards(this.properties.shards())
			.createTable(this.properties.createTable())
			.pollInterval(this.properties.pollInterval())
			.cursorLag(this.properties.cursorLag())
			.sweepInterval(this.properties.sweepInterval())
			.logRetention(this.properties.logRetention())
			.requestTimeout(this.properties.requestTimeout())
			.maxAttempts(this.properties.maxAttempts())
			.build();
	}

}
