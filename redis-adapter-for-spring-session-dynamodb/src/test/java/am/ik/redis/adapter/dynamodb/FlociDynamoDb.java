package am.ik.redis.adapter.dynamodb;

import java.net.URI;

import io.floci.testcontainers.FlociContainer;
import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB every test in this module runs against: the Floci emulator, in a
 * container.
 *
 * <p>
 * AWS publishes no DynamoDB you can run, so — alone among this repository's backends —
 * this one cannot be proven against the real thing, and the emulator is the honest second
 * best. What Floci was measured to be faithful to, and the four things it cannot prove,
 * are recorded in {@code .docs/design/architecture.md} §12.6; the opt-in
 * {@link RealDynamoDbTests} covers what only a real table can.
 *
 * <p>
 * One container serves every test class in the JVM, started on first use and left to
 * Testcontainers to remove. Tests keep out of each other's way by using a table of their
 * own rather than a container each — creating a table costs about a tenth of a second,
 * and it exercises the store's own schema management every time.
 */
final class FlociDynamoDb {

	/**
	 * The emulator to test against. Pinned rather than {@code latest} so that a failure
	 * is reproducible, and moved deliberately when a new Floci is released.
	 */
	static final String IMAGE = "floci/floci:1.5.33";

	private static final FlociContainer container = new FlociContainer(IMAGE);

	private static @Nullable DynamoDbClient client;

	private FlociDynamoDb() {
	}

	/**
	 * Returns a client against the running emulator, starting it if this is the first
	 * call. One client serves the whole JVM — exactly as the server module hands every
	 * database's store the one client Spring Cloud AWS built.
	 * @return the shared client
	 */
	static synchronized DynamoDbClient client() {
		if (!container.isRunning()) {
			container.start();
		}
		DynamoDbClient client = FlociDynamoDb.client;
		if (client == null) {
			client = DynamoDbClient.builder()
				.endpointOverride(URI.create(container.getEndpoint()))
				.region(Region.of(container.getRegion()))
				.credentialsProvider(StaticCredentialsProvider
					.create(AwsBasicCredentials.create(container.getAccessKey(), container.getSecretKey())))
				.httpClient(UrlConnectionHttpClient.create())
				.build();
			FlociDynamoDb.client = client;
		}
		return client;
	}

}
