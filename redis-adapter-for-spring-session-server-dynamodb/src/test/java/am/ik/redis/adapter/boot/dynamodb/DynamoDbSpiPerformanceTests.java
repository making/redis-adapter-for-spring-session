package am.ik.redis.adapter.boot.dynamodb;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import am.ik.redis.adapter.boot.BackendSpiBenchmark;
import am.ik.redis.adapter.boot.PerformanceReport;
import am.ik.redis.adapter.dynamodb.DynamoDbKeyValueStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

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
 * is measured with, so these numbers are read against the in-memory server module's in
 * <em>shape</em> — calls per operation — but not in latency: the store under this harness
 * is the Floci emulator, and its milliseconds say nothing about AWS. Calls per operation
 * is the number worth reporting here, because with DynamoDB it is also the bill.
 *
 * <p>
 * Round trips are counted from the SDK's own execution pipeline
 * ({@link DynamoDbCallCounter}), retries included, in a pass of their own: a single
 * operation between two snapshots, with everything it needs already in place.
 *
 * <p>
 * It asserts nothing about the numbers. Run it with {@code ./mvnw test -Pperformance}.
 */
@Tag("performance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbSpiPerformanceTests {

	private static final Duration SESSION_TTL = Duration.ofMinutes(30);

	private static final String ROUND_TRIPS = "Round trips per operation (DynamoDB)";

	private static final String ROUND_TRIPS_HEADER = """
			| Operation | What DynamoDB was asked |
			| --- | --- |""";

	private static final PerformanceReport report = new PerformanceReport("The SPI, in DynamoDB (Floci emulator)");

	private static DynamoDbCallCounter counter;

	private static DynamoDbClient client;

	private static DynamoDbKeyValueStore dynamodb;

	private static BackendSpiBenchmark benchmark;

	@BeforeAll
	static void startBackend() {
		counter = new DynamoDbCallCounter();
		client = Floci.client(counter);
		dynamodb = DynamoDbKeyValueStore.builder().client(client).tableName("perf-" + UUID.randomUUID()).build();
		benchmark = new BackendSpiBenchmark(report, "dynamodb", dynamodb, counter);
		report.note(Floci.describe());
		report.note("Every case is one call on `KeyValueStore`, the seam a backend plugs into, against that "
				+ "container. The same cases run against `InMemoryKeyValueStore` in the in-memory server module; "
				+ "compare the calls per operation, not the milliseconds — each call is a billed request, and a "
				+ "`TransactWriteItems` is billed at twice a plain write.");
	}

	@AfterAll
	static void closeBackend() {
		dynamodb.close();
		client.close();
		report.write("spi-dynamodb.md");
	}

	/**
	 * How many DynamoDB requests one Redis command costs, which is the number the design
	 * is judged on — and, unlike any other backend, the number the operator is billed.
	 */
	@Test
	@Order(1)
	void howManyTimesEachOperationAsksDynamoDb() {
		String id = "counted-" + UUID.randomUUID();
		byte[] session = session(id);
		byte[] shadow = shadow(id);
		Map<byte[], byte[]> fields = sessionFields(KILOBYTE);
		long deadline = dynamodb.currentTimeMillis() + SESSION_TTL.toMillis();

		count("HGETALL, existing session", () -> dynamodb.hset(session, fields), () -> dynamodb.get(session));
		count("EXISTS", () -> {
		}, () -> dynamodb.exists(session));
		count("HSET, new session (4 fields, 1 KB)", () -> {
		}, () -> dynamodb.hset(session(id + "-new"), fields));
		count("HSET, one field of an existing session", () -> {
		}, () -> dynamodb.hset(session,
				Map.of(b("lastAccessedTime"), b(String.valueOf(dynamodb.currentTimeMillis())))));
		count("APPEND, new shadow key", () -> {
		}, () -> dynamodb.append(shadow, new byte[0]));
		count("PEXPIREAT, session with no TTL yet", () -> {
		}, () -> dynamodb.expireAt(session, deadline));
		// The same TTL pushed out again is what Spring Session issues on every request;
		// with the deadline folded into one conditional update the two cases cost the
		// same here, which is the point of the layout.
		count("PEXPIREAT, same TTL pushed out again", () -> {
		}, () -> dynamodb.expireAt(session, dynamodb.currentTimeMillis() + SESSION_TTL.toMillis()));
		count("PTTL", () -> {
		}, () -> dynamodb.getExpireAt(session));
		count("PERSIST", () -> {
		}, () -> dynamodb.persist(session));
		count("SADD, one member into an empty bucket", () -> {
		}, () -> dynamodb.sadd(b(BackendSpiBenchmark.expirations()), List.of(b("expires:" + id))));
		count("DEL, 1 KB session", () -> dynamodb.hset(session(id + "-del"), fields),
				() -> dynamodb.delete(session(id + "-del")));
		count("RENAME, session to a new id", () -> dynamodb.hset(session(id + "-src"), fields),
				() -> dynamodb.rename(session(id + "-src"), session(id + "-dst")));
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
	 * Where a session stops fitting. DynamoDB's item ceiling is 400 KB, names and values
	 * together, and the store decides the failure before sending so that the emulator's
	 * non-atomic refusal cannot leave half a write behind (architecture §12.6).
	 */
	@Test
	@Order(6)
	void whereASessionStopsFitting() {
		String header = """
				| Attribute size | What happened | Cost |
				| --- | --- | --- |""";
		for (int size : List.of(100 * KILOBYTE, 256 * KILOBYTE, 380 * KILOBYTE, 400 * KILOBYTE, 500 * KILOBYTE)) {
			byte[] key = session("ceiling-" + size + "-" + UUID.randomUUID());
			Map<byte[], byte[]> attribute = Map.of(b("sattr:blob"), payload(size));
			long began = System.nanoTime();
			try {
				dynamodb.hset(key, attribute);
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
	 * Counts what one operation asks DynamoDB, with everything it needs already written.
	 * @param operation what to call it in the report
	 * @param prepare what has to exist first, which is outside the count
	 * @param timed the one operation to count
	 */
	private void count(String operation, Runnable prepare, Runnable timed) {
		prepare.run();
		DynamoDbCallCounter.Snapshot before = counter.begin();
		timed.run();
		report.row(ROUND_TRIPS, ROUND_TRIPS_HEADER, "| %s | %s |".formatted(operation, before.summary()));
	}

	private static String summarize(String message) {
		String oneLine = message.replace('\n', ' ').replace('|', '/');
		return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
	}

}
