package am.ik.redis.adapter.boot.dynamodb;

import java.net.URI;

import io.floci.testcontainers.FlociContainer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB this module's tests and measurements run against: the Floci emulator, in
 * one container shared by every test class in the JVM.
 *
 * <p>
 * AWS publishes no DynamoDB you can run, so this is a fake standing in for the real
 * thing, and every number taken against it is an emulator's number —
 * {@code .docs/design/architecture.md} §12.6 records what that does and does not prove.
 * The image is pinned rather than {@code latest} so a failure is reproducible.
 */
final class Floci {

	static final String IMAGE = "floci/floci:1.5.33";

	private static final FlociContainer container = new FlociContainer(IMAGE);

	private Floci() {
	}

	/**
	 * Returns the running emulator, starting it if this is the first call.
	 * @return the container
	 */
	static synchronized FlociContainer running() {
		if (!container.isRunning()) {
			container.start();
		}
		return container;
	}

	/**
	 * Builds a client against the emulator, over the same {@code url-connection-client}
	 * the shipped server uses.
	 * @param interceptors interceptors to observe the calls with, if any
	 * @return a new client; the caller closes it
	 */
	static DynamoDbClient client(ExecutionInterceptor... interceptors) {
		FlociContainer floci = running();
		return DynamoDbClient.builder()
			.endpointOverride(URI.create(floci.getEndpoint()))
			.region(Region.of(floci.getRegion()))
			.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
			.httpClient(UrlConnectionHttpClient.create())
			.overrideConfiguration(o -> {
				for (ExecutionInterceptor interceptor : interceptors) {
					o.addExecutionInterceptor(interceptor);
				}
			})
			.build();
	}

	/**
	 * Returns how the machine and the emulator should be described beside any numbers
	 * taken here.
	 * @return one markdown line naming what took the measurements
	 */
	static String describe() {
		Runtime runtime = Runtime.getRuntime();
		return ("Measured on %s %s, %d available processors, JVM %s, against %s in a container. Floci is an "
				+ "emulator, not DynamoDB: the latencies are one laptop's and one emulator's and say nothing about "
				+ "AWS; the calls per operation are the store's own behaviour and are the number worth reading — "
				+ "each call is also a billed request.")
			.formatted(System.getProperty("os.name"), System.getProperty("os.arch"), runtime.availableProcessors(),
					Runtime.version(), IMAGE);
	}

}
