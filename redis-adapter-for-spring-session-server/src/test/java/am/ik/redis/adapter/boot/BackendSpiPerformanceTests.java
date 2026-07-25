package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * What one operation of the etcd backend costs, measured against the in-memory backend as
 * the baseline.
 *
 * <p>
 * This measures the SPI rather than the wire, because it is the level where the two
 * backends are the same code path and the difference is the backend itself: the in-memory
 * store separates "etcd is slow" from "the adapter is slow". Everything an operator
 * actually waits for is measured a level up, by {@link EtcdSessionPerformanceTests}
 * through a real client.
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
 * {@code ./mvnw test -Pperformance -pl redis-adapter-for-spring-session-server}.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendSpiPerformanceTests {

	private static final int WARMUP = 20;

	private static final int ITERATIONS = 200;

	private static final int KILOBYTE = 1024;

	private static final Duration SESSION_TTL = Duration.ofMinutes(30);

	private static final String ROUND_TRIPS = "Round trips per operation (etcd)";

	private static final String ROUND_TRIPS_HEADER = """
			| Operation | What etcd was asked |
			| --- | --- |""";

	private static final PerformanceReport report = new PerformanceReport("The SPI, backend against backend");

	private static final AtomicLong bucket = new AtomicLong(1);

	private static String etcdEndpoint;

	private static EtcdKeyValueStore etcd;

	private static InMemoryKeyValueStore inMemory;

	@BeforeAll
	static void startBackends() {
		etcdEndpoint = PerformanceEtcd.endpoint();
		etcd = EtcdKeyValueStore.builder()
			.endpoints(List.of(etcdEndpoint))
			.keyPrefix("/perf-" + UUID.randomUUID() + "/")
			.build();
		inMemory = InMemoryKeyValueStore.create();
		report.note(PerformanceEtcd.describe());
		report.note("Every case is one call on `KeyValueStore`, the seam a backend plugs into. "
				+ "`etcd` is `EtcdKeyValueStore` against that container; `in-memory` is "
				+ "`InMemoryKeyValueStore`, which is the same adapter code with the network taken out.");
	}

	@AfterAll
	static void closeBackends() {
		EtcdMetrics disk = EtcdMetrics.scrape(etcdEndpoint);
		report.note("etcd's own disk latency over the whole run: "
				+ "wal fsync %.2f ms, backend commit %.2f ms on average. No etcd write can be faster than these."
					.formatted(disk.averageMillis("etcd_disk_wal_fsync_duration_seconds"),
							disk.averageMillis("etcd_disk_backend_commit_duration_seconds")));
		etcd.close();
		inMemory.close();
		report.write("spi.md");
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
		// and
		// the case the lease is renewed for; a TTL that really changed is the other one,
		// and
		// is what every PEXPIREAT used to cost.
		count("PEXPIREAT, same TTL pushed out again", () -> {
		}, () -> etcd.expireAt(session, etcd.currentTimeMillis() + SESSION_TTL.toMillis()));
		count("PEXPIREAT, a TTL that really changed", () -> {
		}, () -> etcd.expireAt(session, etcd.currentTimeMillis() + SESSION_TTL.toMillis() + 60_000));
		count("PTTL", () -> {
		}, () -> etcd.getExpireAt(session));
		count("PERSIST", () -> {
		}, () -> etcd.persist(session));
		count("SADD, one member into an empty bucket", () -> {
		}, () -> etcd.sadd(b(expirations()), List.of(b("expires:" + id))));
		count("DEL, 1 KB session", () -> etcd.hset(session(id + "-del"), fields),
				() -> etcd.delete(session(id + "-del")));
		count("RENAME, session to a new id", () -> etcd.hset(session(id + "-src"), fields),
				() -> etcd.rename(session(id + "-src"), session(id + "-dst")));
	}

	/**
	 * One operation at a time, so what is reported is the cost of the operation itself
	 * rather than of a queue in front of it.
	 */
	@Test
	@Order(2)
	void oneOperationAtATime() {
		everyOperation("One operation at a time — etcd", etcd);
		everyOperation("One operation at a time — in-memory", inMemory);
	}

	/**
	 * A session is as big as its attributes, and etcd carries the whole of it on every
	 * write: {@code HSET} of one field rewrites the entire hash, and the value travels as
	 * base64 in a JSON body. This is where an operator's "keep session attributes small"
	 * gets a number.
	 */
	@Test
	@Order(3)
	void sessionsOfEverySize() {
		for (int size : List.of(KILOBYTE, 10 * KILOBYTE, 100 * KILOBYTE)) {
			bySize("Session size — etcd", etcd, size);
			bySize("Session size — in-memory", inMemory, size);
		}
	}

	/**
	 * The expirations bucket is a set that grows all day: every session due to expire in
	 * one minute adds itself to that minute's set. A backend that rewrites the whole
	 * value pays for every member already in it, so the cost of adding the thousandth
	 * session to a minute is not the cost of adding the first.
	 */
	@Test
	@Order(4)
	void addingToABucketThatIsAlreadyBig() {
		for (int members : List.of(1, 100, 1_000, 10_000)) {
			intoABucketOf("Adding to a bucket that already holds members — etcd", etcd, members);
			intoABucketOf("Adding to a bucket that already holds members — in-memory", inMemory, members);
		}
	}

	/**
	 * The one genuinely contended key, driven by as many virtual threads as a deployment
	 * would have connections. Where retrying stops keeping up is the number this is for:
	 * the note says how many attempts each write took and what failed.
	 */
	@Test
	@Order(5)
	void everySessionInTheSameMinute() {
		for (int threads : List.of(4, 16, 64, 256)) {
			contended("One contended bucket — etcd", etcd, threads);
			contended("One contended bucket — in-memory", inMemory, threads);
		}
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

	// --- cases -------------------------------------------------------------------------

	private void everyOperation(String section, KeyValueStore store) {
		String id = "timed-" + UUID.randomUUID();
		byte[] session = session(id);
		byte[] shadow = shadow(id);
		Map<byte[], byte[]> fields = sessionFields(KILOBYTE);
		store.hset(session, fields);
		store.append(shadow, new byte[0]);
		byte[] populatedBucket = b(expirations());
		store.sadd(populatedBucket, filler(1_000));

		add(section, store, "HGETALL, existing session (1 KB)", i -> () -> store.get(session));
		add(section, store, "EXISTS", i -> () -> store.exists(session));
		add(section, store, "HSET, new session (4 fields, 1 KB)", i -> {
			byte[] key = session(id + "-new-" + i);
			return () -> store.hset(key, fields);
		});
		add(section, store, "HSET, one field of an existing session", i -> {
			Map<byte[], byte[]> touched = Map.of(b("lastAccessedTime"), b(String.valueOf(store.currentTimeMillis())));
			return () -> store.hset(session, touched);
		});
		add(section, store, "APPEND, new shadow key", i -> {
			byte[] key = shadow(id + "-shadow-" + i);
			return () -> store.append(key, new byte[0]);
		});
		add(section, store, "PEXPIREAT, existing session", i -> {
			long deadline = store.currentTimeMillis() + SESSION_TTL.toMillis();
			return () -> store.expireAt(session, deadline);
		});
		add(section, store, "PTTL", i -> () -> store.getExpireAt(session));
		add(section, store, "PERSIST", i -> {
			store.expireAt(session, store.currentTimeMillis() + SESSION_TTL.toMillis());
			return () -> store.persist(session);
		});
		add(section, store, "SADD, into a bucket of 1000", i -> {
			byte[] member = b("expires:added-" + i);
			return () -> store.sadd(populatedBucket, List.of(member));
		});
		add(section, store, "SREM, from a bucket of 1000", i -> {
			byte[] member = b("expires:removed-" + i);
			store.sadd(populatedBucket, List.of(member));
			return () -> store.srem(populatedBucket, List.of(member));
		});
		add(section, store, "DEL, 1 KB session", i -> {
			byte[] key = session(id + "-del-" + i);
			store.hset(key, fields);
			return () -> store.delete(key);
		});
		add(section, store, "RENAME, session to a new id", i -> {
			byte[] source = session(id + "-src-" + i);
			byte[] destination = session(id + "-dst-" + i);
			store.hset(source, fields);
			return () -> store.rename(source, destination);
		});
	}

	private void bySize(String section, KeyValueStore store, int size) {
		String id = "sized-" + size + "-" + UUID.randomUUID();
		Map<byte[], byte[]> fields = sessionFields(size);
		byte[] session = session(id);
		store.hset(session, fields);
		String suffix = " (%d KB session)".formatted(size / KILOBYTE);
		add(section, store, "HSET, new session" + suffix, i -> {
			byte[] key = session(id + "-" + i);
			return () -> store.hset(key, fields);
		});
		add(section, store, "HSET, one field" + suffix, i -> {
			Map<byte[], byte[]> touched = Map.of(b("lastAccessedTime"), b(String.valueOf(store.currentTimeMillis())));
			return () -> store.hset(session, touched);
		});
		add(section, store, "HGETALL" + suffix, i -> () -> store.get(session));
	}

	private void intoABucketOf(String section, KeyValueStore store, int members) {
		byte[] key = b(expirations());
		store.sadd(key, filler(members));
		add(section, store, "SADD into a bucket of %,d".formatted(members), i -> {
			byte[] member = b("expires:added-" + i);
			return () -> store.sadd(key, List.of(member));
		}, 50);
	}

	private void contended(String section, KeyValueStore store, int threads) {
		int perThread = 5;
		byte[] key = b(expirations());
		EtcdMetrics before = scrapeIfEtcd(store);
		Measurement measurement = Benchmark.measureConcurrently(
				"SADD into one bucket from %d threads".formatted(threads), threads, perThread, thread -> i -> {
					byte[] member = b("expires:" + thread + "-" + i);
					return () -> store.sadd(key, List.of(member));
				});
		RedisValue landed = store.get(key);
		int members = landed instanceof SetValue set ? set.members().size() : 0;
		report.add(section, measurement.withNote("%d of %d members landed%s".formatted(members, threads * perThread,
				attemptsPerWrite(store, before, threads * perThread))));
	}

	// --- plumbing ----------------------------------------------------------------------

	private void add(String section, KeyValueStore store, String name, Benchmark.Case operation) {
		add(section, store, name, operation, ITERATIONS);
	}

	private void add(String section, KeyValueStore store, String name, Benchmark.Case operation, int iterations) {
		report.add(section, Benchmark.measure(name, WARMUP, iterations, operation));
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

	private static EtcdMetrics scrapeIfEtcd(KeyValueStore store) {
		return store == etcd ? EtcdMetrics.scrape(etcdEndpoint) : new EtcdMetrics(Map.of());
	}

	private static String attemptsPerWrite(KeyValueStore store, EtcdMetrics before, int operations) {
		if (store != etcd) {
			return "";
		}
		EtcdMetrics spent = EtcdMetrics.scrape(etcdEndpoint).since(before);
		return ", " + spent.callsPerOperation(operations);
	}

	/**
	 * Returns the members a bucket is filled with before a case adds to it. One call
	 * rather than one per member, because filling it is not what is being measured.
	 * @param members how many sessions are already due to expire in that minute
	 * @return the members
	 */
	private static List<byte[]> filler(int members) {
		return IntStream.range(0, members).mapToObj(i -> b("expires:filler-" + i)).toList();
	}

	private static Map<byte[], byte[]> sessionFields(int attributeBytes) {
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		fields.put(b("creationTime"), b(String.valueOf(System.currentTimeMillis())));
		fields.put(b("maxInactiveInterval"), b("1800"));
		fields.put(b("lastAccessedTime"), b(String.valueOf(System.currentTimeMillis())));
		fields.put(b("sattr:user"), payload(attributeBytes));
		return fields;
	}

	/**
	 * Returns an attribute of a given size. Serialized session attributes are opaque
	 * bytes, and nothing in the path compresses them, so what they contain does not
	 * matter — only how many there are.
	 * @param bytes how big the attribute is
	 * @return the attribute
	 */
	private static byte[] payload(int bytes) {
		byte[] payload = new byte[bytes];
		for (int i = 0; i < bytes; i++) {
			payload[i] = (byte) ('a' + (i % 26));
		}
		return payload;
	}

	private static String expirations() {
		return SessionKeys.DEFAULT.expirations(bucket.getAndIncrement());
	}

	private static byte[] session(String id) {
		return SessionKeys.DEFAULT.session(id);
	}

	private static byte[] shadow(String id) {
		return SessionKeys.DEFAULT.shadow(id);
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

	private static String summarize(String message) {
		String oneLine = message.replace('\n', ' ').replace('|', '/');
		return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
	}

}
