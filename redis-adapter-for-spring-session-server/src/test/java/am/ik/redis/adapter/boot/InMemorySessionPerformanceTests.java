package am.ik.redis.adapter.boot;

import java.util.List;

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

/**
 * The baseline for {@link EtcdSessionPerformanceTests}: the same application, the same
 * harness, sessions in a map.
 *
 * <p>
 * Nothing here is interesting on its own. It is what says how much of a session's cost is
 * the adapter — the socket, the RESP codec, the command layer, Spring Session itself —
 * and therefore how much of it is etcd. Without this column, a slow number has no
 * explanation.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = InMemorySessionPerformanceTests.SessionApplication.class,
		properties = { "redis-adapter.backend=in-memory" })
class InMemorySessionPerformanceTests {

	private static final int ATTRIBUTE_BYTES = 1024;

	private static final PerformanceReport report = new PerformanceReport(
			"Spring Session over the adapter, sessions in memory (the baseline)");

	@Autowired
	private RedisIndexedSessionRepository sessions;

	@AfterAll
	static void writeReport() {
		report.note("The same harness as the etcd run, against the bundled in-memory backend: "
				+ "one process, no network below the adapter, no replication.");
		report.write("sessions-in-memory.md");
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
		return new SessionPerformanceHarness(this.sessions, report, "in-memory", ATTRIBUTE_BYTES, null);
	}

	@EnableAutoConfiguration
	@EnableRedisIndexedHttpSession(cleanupCron = Scheduled.CRON_DISABLED)
	@Import(AdapterServerTestConfiguration.class)
	static class SessionApplication {

	}

}
