package am.ik.redis.adapter.boot.dynamodb;

import java.net.URI;
import java.time.Duration;

import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the DynamoDB backend contributes to the server, without a DynamoDB.
 *
 * <p>
 * The end-to-end behaviour is covered against the emulator by
 * {@link DynamoDbBackendEndToEndTests}. What is worth asserting without one is the part a
 * deployment gets wrong: that the properties refuse nonsense by naming the property, and
 * that a server which is <em>not</em> using DynamoDB is not made to fail — or made to
 * wait — by a factory it never asked for.
 */
class DynamoDbKeyValueStoreFactoryTests {

	private static final DynamoDbBackendProperties PROPERTIES = new DynamoDbBackendProperties("sessions", true, 4,
			Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(60),
			Duration.ofMillis(250), 2);

	@Test
	void answersToTheNameTheServerLogs() {
		assertThat(new DynamoDbKeyValueStoreFactory(PROPERTIES, unreachableClient()).name()).isEqualTo("dynamodb");
	}

	/**
	 * The factory is built while the application is still starting and is asked for its
	 * stores afterwards, so one that connected as it was built would turn a DynamoDB that
	 * is briefly unreachable into an application that never comes up. Building an SDK
	 * client opens no connection either.
	 */
	@Test
	void connectsToNothingUntilItIsAskedForAStore() {
		assertThatCode(() -> new DynamoDbKeyValueStoreFactory(PROPERTIES, unreachableClient()))
			.doesNotThrowAnyException();
	}

	/**
	 * The store is built even when DynamoDB cannot be reached: the missing table is
	 * logged, the sweeper keeps trying to set it up, and the failure belongs to the first
	 * session rather than to bean creation.
	 */
	@Test
	void createsAStoreForAnEndpointItCannotReach() {
		DynamoDbKeyValueStoreFactory factory = new DynamoDbKeyValueStoreFactory(PROPERTIES, unreachableClient());

		try (DynamoDbKeyValueStore store = (DynamoDbKeyValueStore) factory.create(2)) {
			assertThat(store.tableName()).isEqualTo("sessions");
			assertThat(store.databaseIndex()).isEqualTo(2);
		}
	}

	@Test
	void aBlankTableNameIsRefused() {
		assertThatThrownBy(() -> properties("  ", 4, Duration.ofMillis(500), Duration.ofSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.dynamodb.table-name");
	}

	@Test
	void aShardCountBelowOneIsRefused() {
		assertThatThrownBy(() -> properties("sessions", 0, Duration.ofMillis(500), Duration.ofSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.dynamodb.shards");
	}

	@Test
	void aTimeoutThatIsNotPositiveIsRefused() {
		assertThatThrownBy(() -> new DynamoDbBackendProperties("sessions", true, 4, Duration.ZERO,
				Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(5), 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.dynamodb.poll-interval");
	}

	/**
	 * A retention shorter than the cursor lag would trim log entries a correctly lagging
	 * replica has not read yet, which is a lost session event — so the pair is refused as
	 * the properties bind rather than discovered as a hole in the log.
	 */
	@Test
	void aRetentionInsideTheCursorLagIsRefused() {
		assertThatThrownBy(() -> properties("sessions", 4, Duration.ofSeconds(90), Duration.ofSeconds(60)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redis-adapter.dynamodb.log-retention");
	}

	private static DynamoDbBackendProperties properties(String tableName, int shards, Duration cursorLag,
			Duration logRetention) {
		return new DynamoDbBackendProperties(tableName, true, shards, Duration.ofMillis(100), cursorLag,
				Duration.ofSeconds(1), logRetention, Duration.ofSeconds(5), 10);
	}

	/**
	 * Returns a client pointed at a port nothing listens on, which is what proves that
	 * nothing here connects: a connection attempt would fail loudly and fast.
	 * @return the client
	 */
	private static DynamoDbClient unreachableClient() {
		return DynamoDbClient.builder()
			.endpointOverride(URI.create("http://127.0.0.1:1"))
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
	}

}
