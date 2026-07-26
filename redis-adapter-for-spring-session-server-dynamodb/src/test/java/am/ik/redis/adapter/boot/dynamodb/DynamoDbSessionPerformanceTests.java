package am.ik.redis.adapter.boot.dynamodb;

import java.util.List;
import java.util.UUID;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.boot.SessionPerformanceHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * What a session costs an application when the sessions are in DynamoDB.
 *
 * <p>
 * The same application {@link DynamoDbBackendEndToEndTests} proves correct, measured
 * instead of asserted on: stock Spring Session in indexed mode, a real Lettuce client,
 * the shipped configuration classes, and the Floci emulator standing in for DynamoDB. The
 * latencies are therefore an emulator's; the calls per operation are the store's own
 * behaviour, and they are what the report is for — each one is a billed request. The
 * cleanup job is disabled so that nothing but the harness touches a session.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = DynamoDbSessionPerformanceTests.SessionApplication.class)
class DynamoDbSessionPerformanceTests {

	private static final int ATTRIBUTE_BYTES = 1024;

	private static final PerformanceReport report = new PerformanceReport(
			"Spring Session over the adapter, sessions in DynamoDB (Floci emulator)");

	private static final DynamoDbCallCounter counter = new DynamoDbCallCounter();

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@DynamicPropertySource
	static void dynamoDb(DynamicPropertyRegistry properties) {
		properties.add("redis-adapter.dynamodb.table-name", () -> "perf-sessions-" + UUID.randomUUID());
	}

	@AfterAll
	static void writeReport() {
		report.note(Floci.describe());
		report.note("A session carries one %d-byte attribute, serialized by Spring Session's default serializer. "
			.formatted(ATTRIBUTE_BYTES)
				+ "Notes count the DynamoDB requests one Spring Session operation makes, from the SDK's own "
				+ "execution pipeline, retries included. Each request is billed; a transactional write costs twice "
				+ "a plain one.");
		report.write("sessions-dynamodb.md");
	}

	@Test
	@Order(1)
	void oneRequestAtATime() {
		harness().oneRequestAtATime();
	}

	@Test
	@Order(2)
	void manyRequestsAtOnce() {
		for (int connections : List.of(1, 8, 32, 128)) {
			harness().manyRequestsAtOnce(connections, 10);
		}
	}

	private SessionPerformanceHarness harness() {
		return new SessionPerformanceHarness(this.sessions, report, "dynamodb", ATTRIBUTE_BYTES, counter);
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, DynamoDbBackendConfiguration.class })
	static class SessionApplication {

		/**
		 * The client the factory is handed, instrumented so the harness can count what
		 * every session operation asks DynamoDB. It replaces the one Spring Cloud AWS
		 * would build, and points at the emulator the same way a deployment's endpoint
		 * override would.
		 * @return the instrumented client
		 */
		@Bean
		@Primary
		DynamoDbClient instrumentedDynamoDbClient() {
			return Floci.client(counter);
		}

	}

}
