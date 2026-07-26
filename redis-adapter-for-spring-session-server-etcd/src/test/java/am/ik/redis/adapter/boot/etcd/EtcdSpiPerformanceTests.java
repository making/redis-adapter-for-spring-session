package am.ik.redis.adapter.boot.etcd;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import am.ik.redis.adapter.boot.BackendSpiBenchmark;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static am.ik.redis.adapter.boot.BackendSpiBenchmark.KILOBYTE;
import static am.ik.redis.adapter.boot.BackendSpiBenchmark.b;
import static am.ik.redis.adapter.boot.BackendSpiBenchmark.payload;
import static am.ik.redis.adapter.boot.BackendSpiBenchmark.session;
import static am.ik.redis.adapter.boot.BackendSpiBenchmark.sessionFields;
import static am.ik.redis.adapter.boot.BackendSpiBenchmark.shadow;

/**
 * What one operation of this backend costs, measured at the SPI.
 *
 * <p>
 * The shared cases come from {@link BackendSpiBenchmark} and are the ones every backend
 * is measured with, so these numbers are read against the in-memory server module's: that
 * column is the adapter with the network taken out, and the difference is etcd. The cases
 * that are only about etcd are here, because only etcd has them — how many raft
 * operations one Redis command costs, and where a session stops fitting.
 *
 * <p>
 * Round trips are counted from etcd's own {@code /metrics} rather than by reading the
 * code ({@link EtcdMetrics}), and counted in a pass of their own: a single operation
 * between two scrapes, with everything it needs already in place. The timed pass is
 * separate and does not care what its preparation costs, which is why the two are not
 * merged.
 *
 * <p>
 * It asserts nothing about the numbers. A benchmark that fails when a machine is busy is
 * a benchmark that gets disabled; this one reports, and the conclusions are drawn in
 * {@code .docs/design/etcd-performance.md} by whoever ran it. Run it with
 * {@code ./mvnw test -Pperformance}.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EtcdSpiPerformanceTests {

	private static final Duration SESSION_TTL = Duration.ofMinutes(30);

	private static final String ROUND_TRIPS = "Round trips per operation (etcd)";

	private static final String ROUND_TRIPS_HEADER = """
			| Operation | What etcd was asked |
			| --- | --- |""";

	private static final PerformanceReport report = new PerformanceReport("The SPI, in etcd");

	private static String etcdEndpoint;

	private static EtcdKeyValueStore etcd;

	private static BackendSpiBenchmark benchmark;

	@BeforeAll
	static void startBackend() {
		etcdEndpoint = PerformanceEtcd.endpoint();
		etcd = EtcdKeyValueStore.builder()
			.endpoints(List.of(etcdEndpoint))
			.keyPrefix("/perf-" + UUID.randomUUID() + "/")
			.build();
		benchmark = new BackendSpiBenchmark(report, "etcd", etcd, new EtcdCallCounter(etcdEndpoint));
		report.note(PerformanceEtcd.describe());
		report.note("Every case is one call on `KeyValueStore`, the seam a backend plugs into, against that "
				+ "container. The same cases run against `InMemoryKeyValueStore` in the in-memory server module, "
				+ "which is the same adapter code with the network taken out.");
	}

	@AfterAll
	static void closeBackend() {
		EtcdMetrics disk = EtcdMetrics.scrape(etcdEndpoint);
		report.note("etcd's own disk latency over the whole run: "
				+ "wal fsync %.2f ms, backend commit %.2f ms on average. No etcd write can be faster than these."
					.formatted(disk.averageMillis("etcd_disk_wal_fsync_duration_seconds"),
							disk.averageMillis("etcd_disk_backend_commit_duration_seconds")));
		etcd.close();
		report.write("spi-etcd.md");
	}

	/**
	 * How many raft operations one Redis command costs, which is the number the design is
	 * judged on and the one the two optimizations in architecture §11.6 would change.
	 */
	@Test
	@Order(1)
	void howManyTimesEachOperationAsksEtcd() {
		String id = "counted-" + UUID.randomUUID();
		byte[] session = session(id);
		byte[] shadow = shadow(id);
		Map<byte[], byte[]> fields = sessionFields(KILOBYTE);
		long deadline = etcd.currentTimeMillis() + SESSION_TTL.toMillis();

		count("HGETALL, existing session", () -> etcd.hset(session, fields), () -> etcd.get(session));
		count("EXISTS", () -> {
		}, () -> etcd.exists(session));
		count("HSET, new session (4 fields, 1 KB)", () -> {
		}, () -> etcd.hset(session(id + "-new"), fields));
		count("HSET, one field of an existing session", () -> {
		}, () -> etcd.hset(session, Map.of(b("lastAccessedTime"), b(String.valueOf(etcd.currentTimeMillis())))));
		count("APPEND, new shadow key", () -> {
		}, () -> etcd.append(shadow, new byte[0]));
		count("PEXPIREAT, session with no TTL yet", () -> {
		}, () -> etcd.expireAt(session, deadline));
		// The same TTL pushed out again is what Spring Session issues on every request,
		// and the case the lease is renewed for; a TTL that really changed is the other
		// one, and is what every PEXPIREAT used to cost.
		count("PEXPIREAT, same TTL pushed out again", () -> {
		}, () -> etcd.expireAt(session, etcd.currentTimeMillis() + SESSION_TTL.toMillis()));
		count("PEXPIREAT, a TTL that really changed", () -> {
		}, () -> etcd.expireAt(session, etcd.currentTimeMillis() + SESSION_TTL.toMillis() + 60_000));
		count("PTTL", () -> {
		}, () -> etcd.getExpireAt(session));
		count("PERSIST", () -> {
		}, () -> etcd.persist(session));
		count("SADD, one member into an empty bucket", () -> {
		}, () -> etcd.sadd(b(BackendSpiBenchmark.expirations()), List.of(b("expires:" + id))));
		count("DEL, 1 KB session", () -> etcd.hset(session(id + "-del"), fields),
				() -> etcd.delete(session(id + "-del")));
		count("RENAME, session to a new id", () -> etcd.hset(session(id + "-src"), fields),
				() -> etcd.rename(session(id + "-src"), session(id + "-dst")));
	}

	@Test
	@Order(2)
	void oneOperationAtATime() {
		benchmark.oneOperationAtATime();
	}

	@Test
	@Order(3)
	void sessionsOfEverySize() {
		benchmark.sessionsOfEverySize();
	}

	@Test
	@Order(4)
	void addingToABucketThatIsAlreadyBig() {
		benchmark.addingToABucketThatIsAlreadyBig();
	}

	@Test
	@Order(5)
	void everySessionInTheSameMinute() {
		benchmark.everySessionInTheSameMinute();
	}

	/**
	 * Where a session stops fitting. etcd refuses a request over
	 * {@code --max-request-bytes} (1.5 MiB by default), and the adapter's request is
	 * bigger than the session: the value is base64 in a JSON body, so three bytes of
	 * session cost four on the wire. What the client is told when that happens is worth
	 * knowing too.
	 */
	@Test
	@Order(6)
	void whereASessionStopsFitting() {
		String header = """
				| Attribute size | What happened | Cost |
				| --- | --- | --- |""";
		for (int size : List.of(100 * KILOBYTE, 512 * KILOBYTE, 1_000 * KILOBYTE, 1_400 * KILOBYTE, 1_500 * KILOBYTE,
				1_600 * KILOBYTE, 2_000 * KILOBYTE, 4_000 * KILOBYTE)) {
			byte[] key = session("ceiling-" + size + "-" + UUID.randomUUID());
			Map<byte[], byte[]> attribute = Map.of(b("sattr:blob"), payload(size));
			long began = System.nanoTime();
			try {
				etcd.hset(key, attribute);
				double millis = (System.nanoTime() - began) / 1_000_000.0;
				report.row("The ceiling on one session", header,
						"| %d KB | accepted | %.1f ms |".formatted(size / KILOBYTE, millis));
			}
			catch (RuntimeException ex) {
				report.row("The ceiling on one session", header,
						"| %d KB | refused: %s | %s |".formatted(size / KILOBYTE,
								ex.getClass().getSimpleName() + ", " + summarize(String.valueOf(ex.getMessage())),
								"—"));
			}
		}
	}

	/**
	 * Counts what one operation asks etcd, with everything it needs already written.
	 * @param operation what to call it in the report
	 * @param prepare what has to exist first, which is outside the count
	 * @param timed the one operation to count
	 */
	private void count(String operation, Runnable prepare, Runnable timed) {
		prepare.run();
		EtcdMetrics before = EtcdMetrics.scrape(etcdEndpoint);
		timed.run();
		String calls = EtcdMetrics.scrape(etcdEndpoint).since(before).callSummary();
		report.row(ROUND_TRIPS, ROUND_TRIPS_HEADER, "| %s | %s |".formatted(operation, calls));
	}

	private static String summarize(String message) {
		String oneLine = message.replace('\n', ' ').replace('|', '/');
		return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
	}

}
