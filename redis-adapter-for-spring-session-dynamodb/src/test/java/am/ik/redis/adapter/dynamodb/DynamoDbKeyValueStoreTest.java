package am.ik.redis.adapter.dynamodb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ValueTooLargeException;
import am.ik.redis.adapter.store.ZSetValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code KeyValueStore} contract, against the Floci emulator.
 *
 * <p>
 * Every test runs in a table of its own, so the tests are independent while sharing one
 * container, and every one of them exercises the store's own table creation. The
 * semantics proven here — transactions, conditions, the 400 KB ceiling, all-or-nothing
 * cancellation — are the ones the spike found Floci faithful to; what an emulator cannot
 * prove is covered by construction and by the opt-in {@link RealDynamoDbTests}
 * ({@code .docs/design/architecture.md} §12.6).
 */
class DynamoDbKeyValueStoreTest {

	private static final AtomicInteger tables = new AtomicInteger();

	private DynamoDbKeyValueStore store;

	private RecordingListener listener;

	private String tableName;

	@BeforeEach
	void setUp(TestInfo test) {
		this.tableName = "t" + tables.incrementAndGet() + "-" + test.getTestMethod().orElseThrow().getName();
		this.store = store(this.tableName);
		this.listener = new RecordingListener();
		this.store.addKeyEventListener(this.listener);
	}

	@AfterEach
	void tearDown() {
		this.store.close();
	}

	private static DynamoDbKeyValueStore store(String tableName) {
		return DynamoDbKeyValueStore.builder()
			.client(FlociDynamoDb.client())
			.tableName(tableName)
			.pollInterval(Duration.ofMillis(50))
			.cursorLag(Duration.ofMillis(200))
			.sweepInterval(Duration.ofMillis(200))
			.build();
	}

	// --- STRING ------------------------------------------------------------------------

	@Test
	void appendCreatesZeroLengthStringOnAbsentKey() {
		int length = this.store.append(b("k"), new byte[0]);

		assertThat(length).isZero();
		assertThat(this.store.exists(b("k"))).isTrue();
		assertThat(asString(this.store.get(b("k"))).value()).isEmpty();
	}

	@Test
	void appendGrowsExistingString() {
		assertThat(this.store.append(b("k"), b("ab"))).isEqualTo(2);
		assertThat(this.store.append(b("k"), b("cd"))).isEqualTo(4);

		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("abcd"));
	}

	@Test
	void appendAgainstWrongTypeThrows() {
		this.store.hset(b("h"), fields("f", "v"));

		assertThatThrownBy(() -> this.store.append(b("h"), b("x"))).isInstanceOf(TypeMismatchException.class);
	}

	@Test
	void setStoresAndReadsBackExactBytes() {
		byte[] binary = { 0, 13, 10, -1, 42 }; // NUL CR LF 0xFF, arbitrary

		this.store.set(b("k"), binary);

		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(binary);
	}

	/**
	 * A hash is one item per field, so replacing it with a string has to take those items
	 * with it: a string's removal never looks for them, and a stray would be read as a
	 * field of the next hash written under the same key.
	 */
	@Test
	void setReplacesAValueOfAnotherTypeAndTakesItsItemsWithIt() {
		this.store.hset(b("k"), fields("a", "1"));

		this.store.set(b("k"), b("plain"));

		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("plain"));
		assertThat(this.store.delete(b("k"))).isTrue();
		assertThat(this.store.hset(b("k"), fields("c", "3"))).isEqualTo(1);
		assertThat(asHash(this.store.get(b("k"))).fields()).containsOnlyKeys(ByteArrayKey.of(b("c")));
	}

	@Test
	void setDropsTheDeadlineTheKeyHad() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() + 60_000);

		this.store.set(b("k"), b("fresh"));

		assertThat(this.store.getExpireAt(b("k"))).isNull();
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	@Test
	void setOverAnOverdueKeyAnnouncesTheExpiryFirst() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() - 1);

		this.store.set(b("k"), b("fresh"));

		this.listener.awaitEvent("expired k");
		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("fresh"));
	}

	// --- HASH --------------------------------------------------------------------------

	@Test
	void hsetCreatesAndCountsOnlyNewFields() {
		assertThat(this.store.hset(b("h"), fields("a", "1", "b", "2"))).isEqualTo(2);
		assertThat(this.store.hset(b("h"), fields("b", "3", "c", "4"))).isEqualTo(1);

		HashValue hash = asHash(this.store.get(b("h")));
		assertThat(hash.fields()).hasSize(3);
		assertThat(hash.fields().get(ByteArrayKey.of(b("b")))).containsExactly(b("3"));
	}

	@Test
	void hsetAgainstWrongTypeThrows() {
		this.store.append(b("s"), b("x"));

		assertThatThrownBy(() -> this.store.hset(b("s"), fields("f", "v"))).isInstanceOf(TypeMismatchException.class);
	}

	@Test
	void aFieldBiggerThanOneItemIsRefusedWithValueTooLarge() {
		byte[] blob = new byte[500 * 1024];

		assertThatThrownBy(() -> this.store.hset(b("big"), Map.of(b("f"), blob)))
			.isInstanceOf(ValueTooLargeException.class);
		assertThat(this.store.exists(b("big"))).isFalse();
	}

	// --- SET ---------------------------------------------------------------------------

	@Test
	void saddCountsOnlyNewMembers() {
		assertThat(this.store.sadd(b("s"), List.of(b("a"), b("b"), b("a")))).isEqualTo(2);
		assertThat(this.store.sadd(b("s"), List.of(b("b"), b("c")))).isEqualTo(1);

		assertThat(asSet(this.store.get(b("s"))).members()).containsExactlyInAnyOrder(ByteArrayKey.of(b("a")),
				ByteArrayKey.of(b("b")), ByteArrayKey.of(b("c")));
	}

	@Test
	void sremCountsOnlyRemovedMembers() {
		this.store.sadd(b("s"), List.of(b("a"), b("b")));

		assertThat(this.store.srem(b("s"), List.of(b("a"), b("x")))).isEqualTo(1);
		assertThat(asSet(this.store.get(b("s"))).members()).containsExactly(ByteArrayKey.of(b("b")));
	}

	@Test
	void anEmptiedSetRemovesItsKeyWithoutAnnouncingIt() {
		this.store.sadd(b("s"), List.of(b("a")));

		assertThat(this.store.srem(b("s"), List.of(b("a")))).isEqualTo(1);

		assertThat(this.store.exists(b("s"))).isFalse();
		assertThat(this.store.get(b("s"))).isNull();
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	/**
	 * A transaction takes at most 100 items, so a wider mutation is split. What has to
	 * survive the split is the answer and the value, and — the reason the ceiling is
	 * worth a test — the emulator enforces the same 100 as AWS.
	 */
	@Test
	void aSetWiderThanOneTransactionIsStillOneSet() {
		List<byte[]> members = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			members.add(b("member-" + i));
		}

		assertThat(this.store.sadd(b("wide"), members)).isEqualTo(150);
		assertThat(asSet(this.store.get(b("wide"))).members()).hasSize(150);
		assertThat(this.store.srem(b("wide"), members)).isEqualTo(150);
		assertThat(this.store.exists(b("wide"))).isFalse();
	}

	/**
	 * The counts are exact within one adapter because callers of one key are serialized,
	 * not because the store wins races. Sixteen callers adding the same twenty members
	 * must count each member exactly once between them.
	 */
	@Test
	void concurrentSaddsCountEachMemberOnceWithinOneAdapter() throws Exception {
		List<byte[]> members = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			members.add(b("m-" + i));
		}
		int callers = 16;
		List<Future<Integer>> counted;
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			counted = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				counted.add(executor.submit(() -> this.store.sadd(b("contended"), members)));
			}
		}
		int total = 0;
		for (Future<Integer> count : counted) {
			total += count.get();
		}

		assertThat(total).isEqualTo(20);
		assertThat(asSet(this.store.get(b("contended"))).members()).hasSize(20);
	}

	// --- SORTED SET --------------------------------------------------------------------

	@Test
	void zaddCountsOnlyNewMembersAndMovesScores() {
		assertThat(this.store.zadd(b("z"), scored("a", 1.0, "b", 2.0))).isEqualTo(2);
		assertThat(this.store.zadd(b("z"), scored("b", 9.0, "c", 3.0))).isEqualTo(1);

		ZSetValue zset = asZSet(this.store.get(b("z")));
		assertThat(zset.scores()).hasSize(3);
		assertThat(zset.scores().get(ByteArrayKey.of(b("b")))).isEqualTo(9.0);
	}

	@Test
	void anEmptiedSortedSetRemovesItsKeyWithoutAnnouncingIt() {
		this.store.zadd(b("z"), scored("a", 1.0));

		assertThat(this.store.zrem(b("z"), List.of(b("a"), b("x")))).isEqualTo(1);

		assertThat(this.store.exists(b("z"))).isFalse();
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	// --- DEL / EXISTS ------------------------------------------------------------------

	@Test
	void deleteRemovesALiveKeyAndAnnouncesIt() {
		this.store.hset(b("k"), fields("f", "v"));

		assertThat(this.store.delete(b("k"))).isTrue();

		assertThat(this.store.exists(b("k"))).isFalse();
		this.listener.awaitEvent("deleted k");
	}

	@Test
	void deletingAnAbsentKeyIsASilentNoOp() {
		assertThat(this.store.delete(b("nothing"))).isFalse();
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	@Test
	void deletingAnOverdueKeyAnnouncesTheExpiryInstead() {
		this.store.hset(b("k"), fields("f", "v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() - 10);

		assertThat(this.store.delete(b("k"))).isFalse();

		this.listener.awaitEvent("expired k");
	}

	// --- RENAME ------------------------------------------------------------------------

	@Test
	void renameMovesTheValueAndItsTtlAndAnnouncesNothing() {
		this.store.hset(b("src"), fields("f", "v"));
		long deadline = this.store.currentTimeMillis() + 60_000;
		this.store.expireAt(b("src"), deadline);

		assertThat(this.store.rename(b("src"), b("dst"))).isTrue();

		assertThat(this.store.exists(b("src"))).isFalse();
		assertThat(asHash(this.store.get(b("dst"))).fields()).containsKey(ByteArrayKey.of(b("f")));
		assertThat(this.store.getExpireAt(b("dst"))).isEqualTo(deadline);
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	@Test
	void renamingAnAbsentSourceFails() {
		assertThat(this.store.rename(b("nothing"), b("dst"))).isFalse();
	}

	@Test
	void renameOverwritesALiveDestinationInSilence() {
		this.store.hset(b("src"), fields("from", "src"));
		this.store.hset(b("dst"), fields("old", "value"));

		assertThat(this.store.rename(b("src"), b("dst"))).isTrue();

		HashValue moved = asHash(this.store.get(b("dst")));
		assertThat(moved.fields()).containsOnlyKeys(ByteArrayKey.of(b("from")));
		this.listener.assertSilence(Duration.ofMillis(700));
	}

	@Test
	void renamingAKeyOntoItselfIsANoOp() {
		this.store.hset(b("same"), fields("f", "v"));

		assertThat(this.store.rename(b("same"), b("same"))).isTrue();
		assertThat(asHash(this.store.get(b("same"))).fields()).containsKey(ByteArrayKey.of(b("f")));
	}

	// --- TTL ---------------------------------------------------------------------------

	@Test
	void expireAtIsReadBackAndPersistClearsIt() {
		this.store.hset(b("k"), fields("f", "v"));
		long deadline = this.store.currentTimeMillis() + 60_000;

		assertThat(this.store.expireAt(b("k"), deadline)).isTrue();
		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);
		assertThat(this.store.persist(b("k"))).isTrue();
		assertThat(this.store.getExpireAt(b("k"))).isNull();
		assertThat(this.store.persist(b("k"))).isFalse();
	}

	@Test
	void expireAtOnAnAbsentKeyFails() {
		assertThat(this.store.expireAt(b("nothing"), this.store.currentTimeMillis() + 1_000)).isFalse();
	}

	@Test
	void anOverdueKeyIsGoneOnReadAndTheReadAnnouncesIt() {
		this.store.hset(b("k"), fields("f", "v"));
		this.store.expireAt(b("k"), this.store.currentTimeMillis() - 10);

		assertThat(this.store.get(b("k"))).isNull();
		assertThat(this.store.exists(b("k"))).isFalse();
		this.listener.awaitEvent("expired k");
	}

	/**
	 * The sweeper, end to end: nobody touches the key, its deadline passes, the deadline
	 * index nominates it, the sweeper removes it, and the removal's own log entry is what
	 * every replica — this store included — turns into {@code onExpired}.
	 */
	@Test
	void anUntouchedKeyIsSweptAndAnnounced() {
		this.store.hset(b("abandoned"), fields("f", "v"));
		this.store.expireAt(b("abandoned"), this.store.currentTimeMillis() + 300);

		this.listener.awaitEvent("expired abandoned");
		assertThat(this.store.exists(b("abandoned"))).isFalse();
	}

	// --- TWO STORES, ONE TABLE ---------------------------------------------------------

	@Test
	void aKeyRemovedByAnotherStoreReachesThisOnesListener() {
		this.store.hset(b("shared"), fields("f", "v"));

		try (DynamoDbKeyValueStore other = store(this.tableName)) {
			assertThat(other.delete(b("shared"))).isTrue();
		}

		this.listener.awaitEvent("deleted shared");
	}

	@Test
	void aSessionOutlivesTheStoreThatWroteIt() {
		this.store.hset(b("durable"), fields("f", "v"));
		this.store.close();

		try (DynamoDbKeyValueStore restarted = store(this.tableName)) {
			assertThat(asHash(restarted.get(b("durable"))).fields()).containsKey(ByteArrayKey.of(b("f")));
		}
	}

	@Test
	void databasesAreIndependentKeyspacesInOneTable() {
		try (DynamoDbKeyValueStore other = DynamoDbKeyValueStore.builder()
			.client(FlociDynamoDb.client())
			.tableName(this.tableName)
			.databaseIndex(1)
			.pollInterval(Duration.ofMillis(50))
			.cursorLag(Duration.ofMillis(200))
			.sweepInterval(Duration.ofMillis(200))
			.build()) {
			this.store.hset(b("k"), fields("db", "0"));
			other.hset(b("k"), fields("db", "1"));

			assertThat(asHash(this.store.get(b("k"))).fields().get(ByteArrayKey.of(b("db")))).containsExactly(b("0"));
			assertThat(asHash(other.get(b("k"))).fields().get(ByteArrayKey.of(b("db")))).containsExactly(b("1"));

			other.delete(b("k"));
			assertThat(this.store.exists(b("k"))).isTrue();
		}
	}

	// --- HEALTH ------------------------------------------------------------------------

	@Test
	void checkHealthAnswersAgainstAReachableTable() {
		this.store.checkHealth();
	}

	// --- helpers -----------------------------------------------------------------------

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

	private static Map<byte[], byte[]> fields(String... namesAndValues) {
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			fields.put(b(namesAndValues[i]), b(namesAndValues[i + 1]));
		}
		return fields;
	}

	private static Map<byte[], Double> scored(Object... membersAndScores) {
		Map<byte[], Double> scored = new LinkedHashMap<>();
		for (int i = 0; i < membersAndScores.length; i += 2) {
			scored.put(b((String) membersAndScores[i]), (Double) membersAndScores[i + 1]);
		}
		return scored;
	}

	private static StringValue asString(@Nullable RedisValue value) {
		assertThat(value).isInstanceOf(StringValue.class);
		return (StringValue) requireNonNull(value);
	}

	private static HashValue asHash(@Nullable RedisValue value) {
		assertThat(value).isInstanceOf(HashValue.class);
		return (HashValue) requireNonNull(value);
	}

	private static SetValue asSet(@Nullable RedisValue value) {
		assertThat(value).isInstanceOf(SetValue.class);
		return (SetValue) requireNonNull(value);
	}

	private static ZSetValue asZSet(@Nullable RedisValue value) {
		assertThat(value).isInstanceOf(ZSetValue.class);
		return (ZSetValue) requireNonNull(value);
	}

}
