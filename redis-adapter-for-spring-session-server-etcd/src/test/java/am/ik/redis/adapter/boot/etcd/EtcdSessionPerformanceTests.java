package am.ik.redis.adapter.boot.etcd;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.boot.SessionPerformanceHarness;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a session costs an application when the sessions are in etcd.
 *
 * <p>
 * The same application {@link EtcdBackendEndToEndTests} proves correct, measured instead
 * of asserted on: stock Spring Session in indexed mode, a real Lettuce client, the
 * shipped configuration classes, and a real etcd in a container. The cleanup job is
 * disabled so that nothing but the harness touches a session.
 *
 * <p>
 * The in-memory server module runs the same harness against a store in this process, and
 * the two together are what separates the adapter's own cost from etcd's.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = EtcdSessionPerformanceTests.SessionApplication.class)
class EtcdSessionPerformanceTests {

	private static final int ATTRIBUTE_BYTES = 1024;

	private static final PerformanceReport report = new PerformanceReport(
			"Spring Session over the adapter, sessions in etcd");

	private static @Nullable String endpoint;

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@DynamicPropertySource
	static void etcd(DynamicPropertyRegistry properties) {
		endpoint = PerformanceEtcd.endpoint();
		properties.add("redis-adapter.etcd.endpoints", () -> endpoint);
		properties.add("redis-adapter.etcd.key-prefix", () -> "/perf-sessions-" + UUID.randomUUID() + "/");
	}

	@AfterAll
	static void writeReport() {
		report.note(PerformanceEtcd.describe());
		report.note("A session carries one %d-byte attribute, serialized by Spring Session's default serializer. "
			.formatted(ATTRIBUTE_BYTES)
				+ "Notes count the etcd calls one Spring Session operation makes, taken from etcd's own metrics.");
		report.write("sessions-etcd.md");
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
		return new SessionPerformanceHarness(this.sessions, report, "etcd", ATTRIBUTE_BYTES,
				new EtcdCallCounter(Objects.requireNonNull(endpoint)));
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, EtcdBackendConfiguration.class })
	static class SessionApplication {

	}

}
