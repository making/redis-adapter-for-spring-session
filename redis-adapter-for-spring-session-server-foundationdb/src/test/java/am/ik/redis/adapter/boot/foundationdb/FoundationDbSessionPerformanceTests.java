package am.ik.redis.adapter.boot.foundationdb;

import java.util.List;
import java.util.UUID;

import am.ik.redis.adapter.boot.AdapterServerTestConfiguration;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.boot.SessionPerformanceHarness;
import am.ik.redis.adapter.foundationdb.FdbCluster;
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
 * What a session costs an application when the sessions are in FoundationDB.
 *
 * <p>
 * The same application {@link FoundationDbBackendEndToEndTests} proves correct, measured
 * instead of asserted on: stock Spring Session in indexed mode, a real Lettuce client,
 * the shipped configuration classes, and a real FoundationDB in a container. The cleanup
 * job is disabled so that nothing but the harness and the sweeper touches a session.
 *
 * <p>
 * The in-memory server module runs the same harness against a store in this process, and
 * the two together are what separates the adapter's own cost from FoundationDB's.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = FoundationDbSessionPerformanceTests.SessionApplication.class)
class FoundationDbSessionPerformanceTests {

	private static final int ATTRIBUTE_BYTES = 1024;

	private static final PerformanceReport report = new PerformanceReport(
			"Spring Session over the adapter, sessions in FoundationDB");

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@DynamicPropertySource
	static void foundationDb(DynamicPropertyRegistry properties) {
		properties.add("redis-adapter.foundationdb.cluster-file", FdbCluster::clusterFile);
		properties.add("redis-adapter.foundationdb.key-prefix", () -> "/perf-sessions-" + UUID.randomUUID() + "/");
	}

	@AfterAll
	static void writeReport() {
		report.note(FdbCluster.describe());
		report.note("A session carries one %d-byte attribute, serialized by Spring Session's default serializer. "
			.formatted(ATTRIBUTE_BYTES)
				+ "Notes count what one Spring Session operation makes the cluster do, taken from FoundationDB's "
				+ "own status document — commits being the number this backend's design is argued in.");
		report.write("sessions-foundationdb.md");
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
		return new SessionPerformanceHarness(this.sessions, report, "foundationdb", ATTRIBUTE_BYTES,
				new FoundationDbCallCounter());
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import({ AdapterServerTestConfiguration.class, FoundationDbBackendConfiguration.class })
	static class SessionApplication {

	}

}
