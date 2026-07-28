package am.ik.redis.adapter.foundationdb;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ZSetValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SPI contract, against a real FoundationDB.
 *
 * <p>
 * Everything here that is interesting is FoundationDB's behaviour rather than this code's
 * — that a transaction conflicts, that a range read comes back in tuple order, that a
 * versionstamped key lands in commit order — so a fake would only prove the fake agrees
 * with the assumptions. Each test gets a keyspace of its own under the shared container.
 */
class FoundationDbKeyValueStoreTest {

	private static final Duration SILENCE = Duration.ofMillis(500);

	private String prefix;

	private RecordingListener events;

	private FoundationDbKeyValueStore store;

	@BeforeEach
	void openStore() {
		this.prefix = "/test-" + UUID.randomUUID() + "/";
		this.events = new RecordingListener();
		this.store = open(this.prefix, true);
		this.store.addKeyEventListener(this.events);
	}

	@AfterEach
	void closeStore() {
		this.store.close();
	}

	private static FoundationDbKeyValueStore open(String prefix, boolean sweeping) {
		return FoundationDbKeyValueStore.builder()
			.clusterFile(FdbCluster.clusterFile())
			.keyPrefix(prefix)
			.sweeperEnabled(sweeping)
			.sweepInterval(Duration.ofMillis(200))
			.build();
	}

	// --- strings -----------------------------------------------------------------------

	@Test
	void appendCreatesAStringAndThenGrowsIt() {
		assertThat(this.store.append(key("s"), b("ab"))).isEqualTo(2);
		assertThat(this.store.append(key("s"), b("cd"))).isEqualTo(4);

		assertThat(string(key("s"))).isEqualTo(b("abcd"));
	}

	/**
	 * Appending to indexed mode's shadow key is what Spring Session does on the key whose
	 * expiry announces a session's death, so the append must not disturb the deadline.
	 */
	@Test
	void appendingKeepsTheDeadlineTheKeyAlreadyHad() {
		this.store.append(key("shadow"), new byte[0]);
		long deadline = this.store.currentTimeMillis() + 60_000;
		this.store.expireAt(key("shadow"), deadline);

		this.store.append(key("shadow"), b("x"));

		assertThat(this.store.getExpireAt(key("shadow"))).isEqualTo(deadline);
	}

	@Test
	void setStoresAndReadsBackExactBytes() {
		byte[] binary = { 0, 13, 10, -1, 42 }; // NUL CR LF 0xFF, arbitrary

		this.store.set(key("s"), binary);

		assertThat(string(key("s"))).isEqualTo(binary);
	}

	/**
	 * One key per field is the layout, so replacing a hash with a string has to take the
	 * fields with it — a stray would otherwise be read as part of whatever comes next.
	 */
	@Test
	void setReplacesAValueOfAnotherTypeWholesale() {
		this.store.hset(key("k"), fields("a", "1", "b", "2"));

		this.store.set(key("k"), b("plain"));

		assertThat(string(key("k"))).isEqualTo(b("plain"));
	}

	@Test
	void setDropsTheDeadlineTheKeyHad() {
		this.store.append(key("s"), b("v"));
		this.store.expireAt(key("s"), this.store.currentTimeMillis() + 60_000);

		this.store.set(key("s"), b("fresh"));

		assertThat(this.store.getExpireAt(key("s"))).isNull();
		this.events.assertSilence(SILENCE);
	}

	@Test
	void setOverAnOverdueKeyAnnouncesTheExpiryFirst() {
		this.store.append(key("s"), b("v"));
		this.store.expireAt(key("s"), this.store.currentTimeMillis() - 1);

		this.store.set(key("s"), b("fresh"));

		this.events.awaitEvent("expired " + this.prefix + "s");
		assertThat(string(key("s"))).isEqualTo(b("fresh"));
	}

	// --- hashes ------------------------------------------------------------------------

	@Test
	void hsetCountsOnlyTheFieldsThatWereNotThereBefore() {
		assertThat(this.store.hset(key("h"), fields("a", "1", "b", "2"))).isEqualTo(2);

		assertThat(this.store.hset(key("h"), fields("b", "3", "c", "4"))).isEqualTo(1);

		assertThat(hash(key("h"))).containsOnlyKeys(ByteArrayKey.of(b("a")), ByteArrayKey.of(b("b")),
				ByteArrayKey.of(b("c")));
		assertThat(hash(key("h")).get(ByteArrayKey.of(b("b")))).isEqualTo(b("3"));
	}

	/**
	 * One key per field is the layout, so a field is written and read as a key of its own
	 * — which is what keeps a session hash under FoundationDB's 100,000-byte value
	 * ceiling however many attributes it holds.
	 */
	@Test
	void aHashHoldsMoreThanOneValueCeilingWouldTake() {
		Map<byte[], byte[]> big = new LinkedHashMap<>();
		for (int field = 0; field < 4; field++) {
			big.put(b("attr" + field), new byte[90_000]);
		}

		this.store.hset(key("big"), big);

		assertThat(hash(key("big"))).hasSize(4).allSatisfy((name, value) -> assertThat(value).hasSize(90_000));
	}

	@Test
	void hsetAgainstAStringIsATypeMismatch() {
		this.store.append(key("s"), b("x"));

		assertThatThrownBy(() -> this.store.hset(key("s"), fields("a", "1"))).isInstanceOf(TypeMismatchException.class);
	}

	// --- sets --------------------------------------------------------------------------

	@Test
	void saddCountsOnlyNewMembersAndSremRemovesThem() {
		assertThat(this.store.sadd(key("set"), List.of(b("a"), b("b"), b("a")))).isEqualTo(2);
		assertThat(this.store.sadd(key("set"), List.of(b("b"), b("c")))).isEqualTo(1);

		assertThat(members(key("set"))).containsExactlyInAnyOrder(ByteArrayKey.of(b("a")), ByteArrayKey.of(b("b")),
				ByteArrayKey.of(b("c")));
		assertThat(this.store.srem(key("set"), List.of(b("a"), b("zz")))).isEqualTo(1);
	}

	/**
	 * Redis removes a set that has been emptied, and announces nothing for it — a
	 * {@code del} here would be read by an application as a session having been
	 * destroyed.
	 */
	@Test
	void anEmptiedSetVanishesWithoutAWord() {
		this.store.sadd(key("bucket"), List.of(b("only")));

		assertThat(this.store.srem(key("bucket"), List.of(b("only")))).isEqualTo(1);

		assertThat(this.store.exists(key("bucket"))).isFalse();
		this.events.assertSilence(SILENCE);
	}

	@Test
	void zaddMovesAMemberToANewScoreRatherThanAddingItTwice() {
		assertThat(this.store.zadd(key("z"), scores("a", 1.0, "b", 2.0))).isEqualTo(2);

		assertThat(this.store.zadd(key("z"), scores("a", 9.0))).isZero();

		assertThat(zscores(key("z"))).containsEntry(ByteArrayKey.of(b("a")), 9.0);
		assertThat(this.store.zrem(key("z"), List.of(b("b")))).isEqualTo(1);
	}

	// --- removal and expiry ------------------------------------------------------------

	@Test
	void deletingALiveKeyAnnouncesADelete() {
		this.store.hset(key("h"), fields("a", "1"));

		assertThat(this.store.delete(key("h"))).isTrue();

		this.events.awaitEvent("deleted " + this.prefix + "h");
		assertThat(this.store.exists(key("h"))).isFalse();
	}

	@Test
	void deletingAKeyThatIsNotThereAnnouncesNothing() {
		assertThat(this.store.delete(key("absent"))).isFalse();

		this.events.assertSilence(SILENCE);
	}

	/**
	 * Reading an overdue key removes it, which is what announces the expiry — the
	 * deadline is only a fact on the key until somebody acts on it.
	 */
	@Test
	void readingAnOverdueKeyExpiresItAndSaysSo() {
		this.store.hset(key("h"), fields("a", "1"));
		this.store.expireAt(key("h"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.get(key("h"))).isNull();

		this.events.awaitEvent("expired " + this.prefix + "h");
	}

	/**
	 * Redis expires a key lazily and then answers as if it had already been gone, so
	 * {@code DEL} on an overdue key announces the expiry and returns false.
	 */
	@Test
	void deletingAnOverdueKeyAnnouncesTheExpiryInstead() {
		this.store.hset(key("h"), fields("a", "1"));
		this.store.expireAt(key("h"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.delete(key("h"))).isFalse();

		this.events.awaitEvent("expired " + this.prefix + "h");
	}

	/**
	 * The whole reason this backend has a sweeper: FoundationDB has no TTL of any kind,
	 * so a key nobody ever touches again would otherwise sit there for ever and the
	 * session would never be announced as over.
	 */
	@Test
	void aKeyNobodyTouchesIsSweptAndAnnounced() {
		this.store.hset(key("forgotten"), fields("a", "1"));
		this.store.expireAt(key("forgotten"), this.store.currentTimeMillis() + 300);

		this.events.awaitEvent("expired " + this.prefix + "forgotten");

		assertThat(this.store.exists(key("forgotten"))).isFalse();
	}

	@Test
	void persistTakesTheDeadlineOffAndTheSweeperThenLeavesTheKeyAlone() {
		this.store.hset(key("h"), fields("a", "1"));
		this.store.expireAt(key("h"), this.store.currentTimeMillis() + 300);

		assertThat(this.store.persist(key("h"))).isTrue();

		assertThat(this.store.getExpireAt(key("h"))).isNull();
		this.events.assertSilence(Duration.ofSeconds(1));
		assertThat(this.store.exists(key("h"))).isTrue();
		assertThat(this.store.persist(key("h"))).isFalse();
	}

	@Test
	void expireAtOnAKeyThatIsNotThereChangesNothing() {
		assertThat(this.store.expireAt(key("absent"), this.store.currentTimeMillis() + 1_000)).isFalse();
	}

	// --- rename ------------------------------------------------------------------------

	/**
	 * Changing a session's id is a rename, and a rename must announce nothing at all: a
	 * {@code del} for the source would be read as the session having been destroyed.
	 */
	@Test
	void renameMovesTheValueAndTheDeadlineWithoutAWord() {
		this.store.hset(key("src"), fields("a", "1"));
		long deadline = this.store.currentTimeMillis() + 60_000;
		this.store.expireAt(key("src"), deadline);

		assertThat(this.store.rename(key("src"), key("dst"))).isTrue();

		assertThat(hash(key("dst"))).containsOnlyKeys(ByteArrayKey.of(b("a")));
		assertThat(this.store.getExpireAt(key("dst"))).isEqualTo(deadline);
		assertThat(this.store.exists(key("src"))).isFalse();
		this.events.assertSilence(SILENCE);
	}

	@Test
	void renamingOverALiveKeyOverwritesItInSilence() {
		this.store.hset(key("src"), fields("a", "1"));
		this.store.hset(key("dst"), fields("b", "2"));

		assertThat(this.store.rename(key("src"), key("dst"))).isTrue();

		assertThat(hash(key("dst"))).containsOnlyKeys(ByteArrayKey.of(b("a")));
		this.events.assertSilence(SILENCE);
	}

	/**
	 * A session overwritten without a word is a session the application never hears has
	 * ended, so an overdue destination dies announced before the move lands on it.
	 */
	@Test
	void renamingOverAnOverdueKeyAnnouncesItsExpiryFirst() {
		this.store.hset(key("src"), fields("a", "1"));
		this.store.hset(key("dst"), fields("b", "2"));
		this.store.expireAt(key("dst"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.rename(key("src"), key("dst"))).isTrue();

		this.events.awaitEvent("expired " + this.prefix + "dst");
		assertThat(hash(key("dst"))).containsOnlyKeys(ByteArrayKey.of(b("a")));
	}

	@Test
	void renamingAnOverdueSourceFailsAndAnnouncesItsExpiry() {
		this.store.hset(key("src"), fields("a", "1"));
		this.store.expireAt(key("src"), this.store.currentTimeMillis() - 1);

		assertThat(this.store.rename(key("src"), key("dst"))).isFalse();

		this.events.awaitEvent("expired " + this.prefix + "src");
		assertThat(this.store.exists(key("dst"))).isFalse();
	}

	@Test
	void renamingAKeyThatIsNotThereFails() {
		assertThat(this.store.rename(key("absent"), key("dst"))).isFalse();
	}

	// --- two replicas ------------------------------------------------------------------

	/**
	 * The whole point of a shared backend. A second store on the same keyspace is another
	 * adapter replica: it reads what this one wrote, and what it removes reaches this
	 * one's listeners — which is what an application's session-expired event is made of
	 * when the application is connected to a different replica from the one that noticed.
	 */
	@Test
	void aKeyAnotherReplicaRemovesIsAnnouncedHere() {
		this.store.hset(key("shared"), fields("a", "1"));

		try (FoundationDbKeyValueStore otherReplica = open(this.prefix, false)) {
			assertThat(otherReplica.get(key("shared"))).isInstanceOf(HashValue.class);

			assertThat(otherReplica.delete(key("shared"))).isTrue();
		}

		this.events.awaitEvent("deleted " + this.prefix + "shared");
	}

	@Test
	void aSessionOutlivesTheStoreThatWroteIt() {
		this.store.hset(key("durable"), fields("a", "1"));

		try (FoundationDbKeyValueStore restarted = open(this.prefix, false)) {
			assertThat(hash(restarted, key("durable"))).containsOnlyKeys(ByteArrayKey.of(b("a")));
		}
	}

	/**
	 * The case the layout was chosen for. Every session expiring in the same minute joins
	 * that minute's set, so this is Spring Session's ordinary contended key rather than a
	 * pathological one — and with one FoundationDB key per member, the writers are not
	 * writing the same key at all, so nothing conflicts and nothing is lost.
	 */
	@Test
	void manyWritersAddingToOneBucketLoseNothing() throws Exception {
		int writers = 64;
		CountDownLatch ready = new CountDownLatch(writers);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger added = new AtomicInteger();
		List<Thread> threads = new ArrayList<>();
		for (int writer = 0; writer < writers; writer++) {
			int id = writer;
			threads.add(Thread.ofVirtual().start(() -> {
				ready.countDown();
				try {
					go.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
				added.addAndGet(this.store.sadd(key("expirations"), List.of(b("session-" + id))));
			}));
		}
		assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();

		go.countDown();
		for (Thread thread : threads) {
			thread.join();
		}

		assertThat(added).hasValue(writers);
		assertThat(members(key("expirations"))).hasSize(writers);
	}

	// --- helpers -----------------------------------------------------------------------

	private byte[] key(String name) {
		return b(this.prefix + name);
	}

	private static byte[] b(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static Map<byte[], byte[]> fields(String... namesAndValues) {
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		for (int at = 0; at < namesAndValues.length; at += 2) {
			fields.put(b(namesAndValues[at]), b(namesAndValues[at + 1]));
		}
		return fields;
	}

	private static Map<byte[], Double> scores(Object... membersAndScores) {
		Map<byte[], Double> scores = new LinkedHashMap<>();
		for (int at = 0; at < membersAndScores.length; at += 2) {
			scores.put(b((String) membersAndScores[at]), (Double) membersAndScores[at + 1]);
		}
		return scores;
	}

	private byte[] string(byte[] key) {
		RedisValue value = this.store.get(key);
		assertThat(value).isInstanceOf(StringValue.class);
		return ((StringValue) value).value();
	}

	private Map<ByteArrayKey, byte[]> hash(byte[] key) {
		return hash(this.store, key);
	}

	private static Map<ByteArrayKey, byte[]> hash(FoundationDbKeyValueStore store, byte[] key) {
		RedisValue value = store.get(key);
		assertThat(value).isInstanceOf(HashValue.class);
		return ((HashValue) value).fields();
	}

	private java.util.Set<ByteArrayKey> members(byte[] key) {
		RedisValue value = this.store.get(key);
		assertThat(value).isInstanceOf(SetValue.class);
		return ((SetValue) value).members();
	}

	private Map<ByteArrayKey, Double> zscores(byte[] key) {
		RedisValue value = this.store.get(key);
		assertThat(value).isInstanceOf(ZSetValue.class);
		return ((ZSetValue) value).scores();
	}

}
