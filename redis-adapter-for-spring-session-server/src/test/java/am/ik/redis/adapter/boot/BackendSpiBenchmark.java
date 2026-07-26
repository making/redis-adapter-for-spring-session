package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.ValueTooLargeException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * What one operation of a backend costs, measured at the SPI.
 *
 * <p>
 * This is the level where every backend is the same code path and the difference is the
 * backend itself, so the same cases run against all of them and the numbers are
 * comparable: the in-memory store is what separates "the store is slow" from "the adapter
 * is slow". Everything an operator actually waits for is measured a level up, by
 * {@link SessionPerformanceHarness}, through a real client.
 *
 * <p>
 * The cases are the ones Spring Session actually issues, and each is named after the
 * Redis command it stands behind. Nothing is asserted about the numbers. A benchmark that
 * fails when a machine is busy is a benchmark that gets disabled; this one reports, and
 * the conclusions are drawn by whoever ran it.
 *
 * <p>
 * A backend's own server module runs this against its store and adds whatever only it can
 * measure — how many round trips one operation cost, where a value stops fitting.
 */
public final class BackendSpiBenchmark {

	/** How big a session attribute is in the cases that do not vary it. */
	public static final int KILOBYTE = 1024;

	private static final int WARMUP = 20;

	private static final int ITERATIONS = 200;

	private static final Duration SESSION_TTL = Duration.ofMinutes(30);

	/** Every case takes a bucket of its own, so no two of them contend by accident. */
	private static final AtomicLong bucket = new AtomicLong(1);

	private final PerformanceReport report;

	private final String backend;

	private final KeyValueStore store;

	private final CallCounter counter;

	/**
	 * @param report where the numbers go
	 * @param backend what to call this backend in the report
	 * @param store the backend to measure
	 * @param counter what counts the calls the backend made, or {@link CallCounter#NONE}
	 */
	public BackendSpiBenchmark(PerformanceReport report, String backend, KeyValueStore store, CallCounter counter) {
		this.report = report;
		this.backend = backend;
		this.store = store;
		this.counter = counter;
	}

	/**
	 * Measures each operation on its own, so what is reported is the cost of the
	 * operation itself rather than of a queue in front of it.
	 */
	public void oneOperationAtATime() {
		String section = "One operation at a time — " + this.backend;
		String id = "timed-" + UUID.randomUUID();
		byte[] session = session(id);
		byte[] shadow = shadow(id);
		Map<byte[], byte[]> fields = sessionFields(KILOBYTE);
		this.store.hset(session, fields);
		this.store.append(shadow, new byte[0]);
		byte[] populatedBucket = b(expirations());
		this.store.sadd(populatedBucket, filler(1_000));

		add(section, "HGETALL, existing session (1 KB)", i -> () -> this.store.get(session));
		add(section, "EXISTS", i -> () -> this.store.exists(session));
		add(section, "HSET, new session (4 fields, 1 KB)", i -> {
			byte[] key = session(id + "-new-" + i);
			return () -> this.store.hset(key, fields);
		});
		add(section, "HSET, one field of an existing session", i -> {
			Map<byte[], byte[]> touched = Map.of(b("lastAccessedTime"),
					b(String.valueOf(this.store.currentTimeMillis())));
			return () -> this.store.hset(session, touched);
		});
		add(section, "APPEND, new shadow key", i -> {
			byte[] key = shadow(id + "-shadow-" + i);
			return () -> this.store.append(key, new byte[0]);
		});
		add(section, "PEXPIREAT, existing session", i -> {
			long deadline = this.store.currentTimeMillis() + SESSION_TTL.toMillis();
			return () -> this.store.expireAt(session, deadline);
		});
		add(section, "PTTL", i -> () -> this.store.getExpireAt(session));
		add(section, "PERSIST", i -> {
			this.store.expireAt(session, this.store.currentTimeMillis() + SESSION_TTL.toMillis());
			return () -> this.store.persist(session);
		});
		add(section, "SADD, into a bucket of 1000", i -> {
			byte[] member = b("expires:added-" + i);
			return () -> this.store.sadd(populatedBucket, List.of(member));
		});
		add(section, "SREM, from a bucket of 1000", i -> {
			byte[] member = b("expires:removed-" + i);
			this.store.sadd(populatedBucket, List.of(member));
			return () -> this.store.srem(populatedBucket, List.of(member));
		});
		add(section, "DEL, 1 KB session", i -> {
			byte[] key = session(id + "-del-" + i);
			this.store.hset(key, fields);
			return () -> this.store.delete(key);
		});
		add(section, "RENAME, session to a new id", i -> {
			byte[] source = session(id + "-src-" + i);
			byte[] destination = session(id + "-dst-" + i);
			this.store.hset(source, fields);
			return () -> this.store.rename(source, destination);
		});
	}

	/**
	 * A session is as big as its attributes, and a backend that rewrites the whole value
	 * carries all of it on every write: {@code HSET} of one field rewrites the entire
	 * hash. This is where an operator's "keep session attributes small" gets a number.
	 *
	 * <p>
	 * A size a backend simply refuses is a fact about that backend rather than a broken
	 * measurement — FoundationDB caps one value at 100,000 bytes — so it is reported and
	 * the remaining sizes are still measured. The report is what says which sizes a
	 * backend has numbers for.
	 */
	public void sessionsOfEverySize() {
		String section = "Session size — " + this.backend;
		for (int size : List.of(KILOBYTE, 10 * KILOBYTE, 100 * KILOBYTE)) {
			String id = "sized-" + size + "-" + UUID.randomUUID();
			Map<byte[], byte[]> fields = sessionFields(size);
			byte[] session = session(id);
			try {
				this.store.hset(session, fields);
			}
			catch (ValueTooLargeException e) {
				this.report.note("A %d KB session is more than the %s backend takes in one attribute, so that size has "
					.formatted(size / KILOBYTE, this.backend) + "no row below: " + e.getMessage());
				continue;
			}
			String suffix = " (%d KB session)".formatted(size / KILOBYTE);
			add(section, "HSET, new session" + suffix, i -> {
				byte[] key = session(id + "-" + i);
				return () -> this.store.hset(key, fields);
			});
			add(section, "HSET, one field" + suffix, i -> {
				Map<byte[], byte[]> touched = Map.of(b("lastAccessedTime"),
						b(String.valueOf(this.store.currentTimeMillis())));
				return () -> this.store.hset(session, touched);
			});
			add(section, "HGETALL" + suffix, i -> () -> this.store.get(session));
		}
	}

	/**
	 * The expirations bucket is a set that grows all day: every session due to expire in
	 * one minute adds itself to that minute's set. A backend that rewrites the whole
	 * value pays for every member already in it, so the cost of adding the thousandth
	 * session to a minute is not the cost of adding the first.
	 */
	public void addingToABucketThatIsAlreadyBig() {
		String section = "Adding to a bucket that already holds members — " + this.backend;
		for (int members : List.of(1, 100, 1_000, 10_000)) {
			byte[] key = b(expirations());
			this.store.sadd(key, filler(members));
			add(section, "SADD into a bucket of %,d".formatted(members), i -> {
				byte[] member = b("expires:added-" + i);
				return () -> this.store.sadd(key, List.of(member));
			}, 50);
		}
	}

	/**
	 * The one genuinely contended key, driven by as many virtual threads as a deployment
	 * would have connections. Where retrying stops keeping up is the number this is for:
	 * the note says how many members landed and what the backend was asked for them.
	 */
	public void everySessionInTheSameMinute() {
		String section = "One contended bucket — " + this.backend;
		int perThread = 5;
		for (int threads : List.of(4, 16, 64, 256)) {
			byte[] key = b(expirations());
			CallCounter.Snapshot before = this.counter.begin();
			Measurement measurement = Benchmark.measureConcurrently(
					"SADD into one bucket from %d threads".formatted(threads), threads, perThread, thread -> i -> {
						byte[] member = b("expires:" + thread + "-" + i);
						return () -> this.store.sadd(key, List.of(member));
					});
			RedisValue landed = this.store.get(key);
			int members = landed instanceof SetValue set ? set.members().size() : 0;
			String calls = before.perOperation(threads * perThread);
			this.report.add(section, measurement.withNote("%d of %d members landed%s".formatted(members,
					threads * perThread, calls.isEmpty() ? "" : ", " + calls)));
		}
	}

	private void add(String section, String name, Benchmark.Case operation) {
		add(section, name, operation, ITERATIONS);
	}

	private void add(String section, String name, Benchmark.Case operation, int iterations) {
		this.report.add(section, Benchmark.measure(name, WARMUP, iterations, operation));
	}

	/**
	 * Returns the members a bucket is filled with before a case adds to it. One call
	 * rather than one per member, because filling it is not what is being measured.
	 * @param members how many sessions are already due to expire in that minute
	 * @return the members
	 */
	public static List<byte[]> filler(int members) {
		return IntStream.range(0, members).mapToObj(i -> b("expires:filler-" + i)).toList();
	}

	/**
	 * Returns the fields of a session carrying one attribute of the given size.
	 * @param attributeBytes how big the attribute is
	 * @return the fields, as Spring Session writes them
	 */
	public static Map<byte[], byte[]> sessionFields(int attributeBytes) {
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
	public static byte[] payload(int bytes) {
		byte[] payload = new byte[bytes];
		for (int i = 0; i < bytes; i++) {
			payload[i] = (byte) ('a' + (i % 26));
		}
		return payload;
	}

	/**
	 * Returns the key of an expirations bucket nothing else is using.
	 * @return the key, as Spring Session names it
	 */
	public static String expirations() {
		return SessionKeys.DEFAULT.expirations(bucket.getAndIncrement());
	}

	/**
	 * Returns the key of a session.
	 * @param id the session id
	 * @return the key, as Spring Session names it
	 */
	public static byte[] session(String id) {
		return SessionKeys.DEFAULT.session(id);
	}

	/**
	 * Returns the key of a session's shadow, the one whose expiry is the notification.
	 * @param id the session id
	 * @return the key, as Spring Session names it
	 */
	public static byte[] shadow(String id) {
		return SessionKeys.DEFAULT.shadow(id);
	}

	/**
	 * Returns the UTF-8 bytes of a key or field name.
	 * @param text the name
	 * @return its bytes
	 */
	public static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
