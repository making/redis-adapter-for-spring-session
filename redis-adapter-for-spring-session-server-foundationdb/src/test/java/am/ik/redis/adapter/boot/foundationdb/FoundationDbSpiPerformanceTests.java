package am.ik.redis.adapter.boot.foundationdb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

import am.ik.redis.adapter.boot.BackendSpiBenchmark;
import am.ik.redis.adapter.boot.CallCounter;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.foundationdb.FdbCluster;
import am.ik.redis.adapter.foundationdb.FoundationDbKeyValueStore;
import org.jspecify.annotations.Nullable;
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
 * is measured with, so these numbers are read against the in-memory server module's —
 * that column is the adapter with the network taken out, and the difference is
 * FoundationDB. The cases that are only about FoundationDB are here: how many commits one
 * Redis command costs, which is the unit this backend's design is argued in, and where a
 * session attribute stops fitting.
 *
 * <p>
 * It asserts nothing about the numbers. A benchmark that fails when a machine is busy is
 * a benchmark that gets disabled; this one reports, and the conclusions are drawn by
 * whoever ran it. Run it with {@code ./mvnw test -Pperformance}.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FoundationDbSpiPerformanceTests {

	private static final Duration SESSION_TTL = Duration.ofMinutes(30);

	/**
	 * How many times an operation runs inside one count, to dwarf the sampling window.
	 */
	private static final int COUNTED = 100;

	private static final String COMMITS = "What FoundationDB is made to do, per operation";

	private static final String COMMITS_HEADER = """
			| Operation | What the cluster did |
			| --- | --- |""";

	private static final PerformanceReport report = new PerformanceReport("The SPI, in FoundationDB");

	private static FoundationDbKeyValueStore foundationDb;

	private static BackendSpiBenchmark benchmark;

	private static CallCounter calls;

	@BeforeAll
	static void startBackend() {
		calls = new FoundationDbCallCounter();
		foundationDb = FoundationDbKeyValueStore.builder()
			.clusterFile(FdbCluster.clusterFile())
			.keyPrefix("/perf-" + UUID.randomUUID() + "/")
			// The counts come from the cluster, which cannot tell this store's commits
			// from anything else's - so the two things this store would otherwise do in
			// the background are turned off, and what is left in the counters is the
			// operation being measured. What the sweeper costs is measured through a real
			// client by FoundationDbSessionPerformanceTests, where it is on.
			.sweeperEnabled(false)
			.watchTimeout(Duration.ofMinutes(10))
			.build();
		benchmark = new BackendSpiBenchmark(report, "foundationdb", foundationDb, calls);
		report.note(FdbCluster.describe());
		report.note("Every case is one call on `KeyValueStore`, the seam a backend plugs into, against that "
				+ "container. The same cases run against `InMemoryKeyValueStore` in the in-memory server module, "
				+ "which is the same adapter code with the network taken out.");
		report.note("Counts come from the cluster's own status document, not from the adapter. **Commits** is the "
				+ "number this design is argued in: a whole session save is meant to be one, against the six raft "
				+ "writes architecture.md §11.6 measured for etcd and the eight or so billed requests §12.7 "
				+ "measured for DynamoDB. That document is a periodic snapshot, so every count waits for it to "
				+ "catch up; the sweeper and the watch renewal are off here, so nothing but the measured "
				+ "operation is in it.");
	}

	@AfterAll
	static void closeBackend() {
		foundationDb.close();
		report.write("spi-foundationdb.md");
	}

	/**
	 * How many commits one Redis command costs. This is the number §13.8 of the design is
	 * about, and the one a multi-key transaction is supposed to change.
	 *
	 * <p>
	 * Each operation is counted over a run of {@value #COUNTED} rather than once, because
	 * the cluster's counters are a periodic snapshot: a single operation falls somewhere
	 * inside one sampling window and cannot be told from its neighbours, while a hundred
	 * of them dwarf the window's error. What is reported is therefore an average, which
	 * is what a claim like "a save is one commit" means anyway.
	 */
	@Test
	@Order(1)
	void whatEachOperationMakesTheClusterDo() {
		String id = "counted-" + UUID.randomUUID();
		byte[] session = session(id);
		Map<byte[], byte[]> fields = sessionFields(KILOBYTE);
		foundationDb.hset(session, fields);

		count("HGETALL, existing session", i -> () -> foundationDb.get(session));
		count("EXISTS", i -> () -> foundationDb.exists(session));
		count("HSET, new session (4 fields, 1 KB)", i -> {
			byte[] key = session(id + "-new-" + i);
			return () -> foundationDb.hset(key, fields);
		});
		count("HSET, one field of an existing session", i -> {
			Map<byte[], byte[]> touched = Map.of(b("lastAccessedTime"),
					b(String.valueOf(foundationDb.currentTimeMillis())));
			return () -> foundationDb.hset(session, touched);
		});
		count("APPEND, new shadow key", i -> {
			byte[] key = shadow(id + "-shadow-" + i);
			return () -> foundationDb.append(key, new byte[0]);
		});
		count("PEXPIREAT, existing session", i -> {
			long deadline = foundationDb.currentTimeMillis() + SESSION_TTL.toMillis();
			return () -> foundationDb.expireAt(session, deadline);
		});
		count("PTTL", i -> () -> foundationDb.getExpireAt(session));
		count("PERSIST", i -> {
			foundationDb.expireAt(session, foundationDb.currentTimeMillis() + SESSION_TTL.toMillis());
			return () -> foundationDb.persist(session);
		});
		count("SADD, one member into a bucket", i -> {
			byte[] member = b("expires:" + id + "-" + i);
			return () -> foundationDb.sadd(b(BackendSpiBenchmark.expirations()), List.of(member));
		});
		count("DEL, 1 KB session", i -> {
			byte[] key = session(id + "-del-" + i);
			foundationDb.hset(key, fields);
			return () -> foundationDb.delete(key);
		});
		count("RENAME, session to a new id", i -> {
			byte[] source = session(id + "-src-" + i);
			byte[] destination = session(id + "-dst-" + i);
			foundationDb.hset(source, fields);
			return () -> foundationDb.rename(source, destination);
		});
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
	 * Where a session attribute stops fitting. FoundationDB caps one value at exactly
	 * 100,000 bytes, which is fifteen times smaller than etcd's ceiling and is the reason
	 * this backend keeps one key per hash field: the limit binds on an attribute rather
	 * than on the session.
	 */
	@Test
	@Order(6)
	void whereASessionAttributeStopsFitting() {
		String header = """
				| Attribute size | What happened | Cost |
				| --- | --- | --- |""";
		for (int size : List.of(10 * KILOBYTE, 50 * KILOBYTE, 90 * KILOBYTE, 98 * KILOBYTE, 100 * KILOBYTE,
				200 * KILOBYTE)) {
			byte[] key = session("ceiling-" + size + "-" + UUID.randomUUID());
			Map<byte[], byte[]> attribute = Map.of(b("sattr:blob"), payload(size));
			long began = System.nanoTime();
			try {
				foundationDb.hset(key, attribute);
				double millis = (System.nanoTime() - began) / 1_000_000.0;
				report.row("The ceiling on one attribute", header,
						"| %d KB | accepted | %.1f ms |".formatted(size / KILOBYTE, millis));
			}
			catch (RuntimeException ex) {
				report.row("The ceiling on one attribute", header, "| %d KB | refused: %s | — |"
					.formatted(size / KILOBYTE, ex.getClass().getSimpleName() + ", " + summarize(ex.getMessage())));
			}
		}
	}

	/**
	 * Counts what a run of one operation makes the cluster do, with everything each of
	 * them needs already written before the counting starts.
	 * @param operation what to call it in the report
	 * @param prepared what to run once, given the iteration number; whatever it does
	 * before returning is preparation and is outside the count
	 */
	private void count(String operation, IntFunction<Runnable> prepared) {
		List<Runnable> run = new ArrayList<>();
		for (int iteration = 0; iteration < COUNTED; iteration++) {
			run.add(prepared.apply(iteration));
		}
		CallCounter.Snapshot before = calls.begin();
		run.forEach(Runnable::run);
		report.row(COMMITS, COMMITS_HEADER, "| %s | %s |".formatted(operation, before.perOperation(COUNTED)));
	}

	private static String summarize(@Nullable String message) {
		String oneLine = String.valueOf(message).replace('\n', ' ').replace('|', '/');
		return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
	}

}
