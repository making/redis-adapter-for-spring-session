package am.ik.redis.adapter.inmemory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ZSetValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class InMemoryKeyValueStoreTest {

	private final AtomicLong clock = new AtomicLong(1_000_000L);

	private InMemoryKeyValueStore store;

	private RecordingListener listener;

	@BeforeEach
	void setUp() {
		// Deterministic passive-expiry tests: injected clock, sweeper disabled.
		this.store = InMemoryKeyValueStore.builder().clock(this.clock::get).sweeperEnabled(false).build();
		this.listener = new RecordingListener();
		this.store.addKeyEventListener(this.listener);
	}

	@AfterEach
	void tearDown() {
		this.store.close();
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
		this.store.hset(b("h"), Map.of(b("f"), b("v")));
		assertThatThrownBy(() -> this.store.append(b("h"), b("x"))).isInstanceOf(TypeMismatchException.class);
	}

	@Test
	void setStoresAndReadsBackExactBytes() {
		byte[] binary = { 0, 13, 10, -1, 42 }; // NUL CR LF 0xFF, arbitrary
		this.store.set(b("k"), binary, null);
		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(binary);
	}

	@Test
	void setReplacesAValueOfAnotherTypeWholesale() {
		this.store.hset(b("k"), Map.of(b("f"), b("v")));
		this.store.set(b("k"), b("plain"), null);
		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("plain"));
	}

	@Test
	void setDropsTheDeadlineTheKeyHad() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.clock.get() + 1000);

		this.store.set(b("k"), b("fresh"), null);

		assertThat(this.store.getExpireAt(b("k"))).isNull();
		this.clock.addAndGet(5000);
		assertThat(this.store.exists(b("k"))).isTrue();
		assertThat(this.listener.expired).isEmpty();
	}

	/**
	 * The deadline lands in the same entry as the value, so the key is never there
	 * without it and nothing has to follow the write to put it on.
	 */
	@Test
	void setWithADeadlineWritesItWithTheValue() {
		long deadline = this.clock.get() + 1000;

		this.store.set(b("k"), b("v"), deadline);

		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);
		this.clock.addAndGet(1000);
		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.listener.expired).containsExactly("k");
	}

	/** A deadline already passed is written like any other: the key dies announced. */
	@Test
	void setWithADeadlineAlreadyPassedLeavesAKeyThatIsGoneWhenTouched() {
		this.store.set(b("k"), b("v"), this.clock.get() - 1);

		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.listener.expired).containsExactly("k");
	}

	@Test
	void setOverAnOverdueKeyAnnouncesTheExpiryFirst() {
		this.store.append(b("k"), b("v"));
		this.store.expireAt(b("k"), this.clock.get() - 1);

		this.store.set(b("k"), b("fresh"), null);

		assertThat(this.listener.expired).containsExactly("k");
		assertThat(asString(this.store.get(b("k"))).value()).containsExactly(b("fresh"));
	}

	/** Overwriting a key is not deleting it, and a session event must not be invented. */
	@Test
	void setOverALiveKeyFiresNothing() {
		this.store.append(b("k"), b("v"));

		this.store.set(b("k"), b("fresh"), null);

		assertThat(this.listener.deleted).isEmpty();
		assertThat(this.listener.expired).isEmpty();
	}

	// --- HASH --------------------------------------------------------------------------

	@Test
	void hsetStoresAndReadsBackExactBytes() {
		byte[] binary = { 0, 13, 10, -1, 42 }; // NUL CR LF 0xFF, arbitrary
		Map<byte[], byte[]> fields = new LinkedHashMap<>();
		fields.put(b("creationTime"), binary);
		assertThat(this.store.hset(b("h"), fields)).isEqualTo(1);

		HashValue hash = asHash(this.store.get(b("h")));
		assertThat(hash.fields().get(ByteArrayKey.of(b("creationTime")))).containsExactly(binary);
	}

	@Test
	void hsetOfMissingKeyReadsAsAbsent() {
		assertThat(this.store.get(b("nope"))).isNull();
		assertThat(this.store.exists(b("nope"))).isFalse();
	}

	@Test
	void hsetReturnsNewFieldCountAndMergesLastWins() {
		assertThat(this.store.hset(b("h"), linkedMap(b("a"), b("1"), b("b"), b("2")))).isEqualTo(2);
		// "a" already exists (updated), "c" is new -> count 1
		assertThat(this.store.hset(b("h"), linkedMap(b("a"), b("9"), b("c"), b("3")))).isEqualTo(1);

		HashValue hash = asHash(this.store.get(b("h")));
		assertThat(hash.fields()).hasSize(3);
		assertThat(hash.fields().get(ByteArrayKey.of(b("a")))).containsExactly(b("9"));
	}

	// --- SET ---------------------------------------------------------------------------

	@Test
	void setAddRemoveMembersRoundTripByValue() {
		byte[] m1 = { 1, 2, 3 };
		byte[] m1copy = { 1, 2, 3 };
		byte[] m2 = { 4, 5 };
		assertThat(this.store.sadd(b("s"), List.of(m1, m2))).isEqualTo(2);
		assertThat(this.store.sadd(b("s"), List.of(m1copy))).isZero(); // value-equal,
																		// already present

		SetValue set = asSet(this.store.get(b("s")));
		assertThat(set.members()).containsExactlyInAnyOrder(ByteArrayKey.of(m1), ByteArrayKey.of(m2));

		assertThat(this.store.srem(b("s"), List.of(m1copy))).isEqualTo(1); // value-equal
																			// removal
		assertThat(asSet(this.store.get(b("s"))).members()).containsExactly(ByteArrayKey.of(m2));
	}

	@Test
	void sremEmptyingSetRemovesKeyWithoutDeleteEvent() {
		this.store.sadd(b("s"), List.of(b("x")));
		assertThat(this.store.srem(b("s"), List.of(b("x")))).isEqualTo(1);
		assertThat(this.store.exists(b("s"))).isFalse();
		assertThat(this.listener.deleted).isEmpty();
	}

	// --- SORTED SET --------------------------------------------------------------------

	@Test
	void sortedSetAddRemoveMembersRoundTripByValue() {
		byte[] m1 = { 1, 2, 3 };
		byte[] m1copy = { 1, 2, 3 };
		byte[] m2 = { 4, 5 };
		assertThat(this.store.zadd(b("z"), scoredMap(m1, 1000.0, m2, 2000.0))).isEqualTo(2);
		// value-equal, so it moves rather than being added again
		assertThat(this.store.zadd(b("z"), scoredMap(m1copy, 3000.0))).isZero();

		ZSetValue sortedSet = asSortedSet(this.store.get(b("z")));
		assertThat(sortedSet.scores()).containsOnly(entry(ByteArrayKey.of(m1), 3000.0),
				entry(ByteArrayKey.of(m2), 2000.0));

		assertThat(this.store.zrem(b("z"), List.of(m1copy))).isEqualTo(1); // value-equal
		assertThat(asSortedSet(this.store.get(b("z"))).scores()).containsOnlyKeys(ByteArrayKey.of(m2));
	}

	@Test
	void zremEmptyingSortedSetRemovesKeyWithoutDeleteEvent() {
		this.store.zadd(b("z"), scoredMap(b("x"), 1000.0));
		assertThat(this.store.zrem(b("z"), List.of(b("x")))).isEqualTo(1);
		assertThat(this.store.exists(b("z"))).isFalse();
		assertThat(this.listener.deleted).isEmpty();
	}

	@Test
	void sortedSetOperationAgainstWrongTypeThrows() {
		this.store.sadd(b("s"), List.of(b("x")));
		assertThatThrownBy(() -> this.store.zadd(b("s"), scoredMap(b("x"), 1000.0)))
			.isInstanceOf(TypeMismatchException.class);
		assertThatThrownBy(() -> this.store.zrem(b("s"), List.of(b("x")))).isInstanceOf(TypeMismatchException.class);
	}

	// --- DELETE ------------------------------------------------------------------------

	@Test
	void deleteFiresOnDeletedOnceForExistingKeyAndIsIdempotent() {
		this.store.append(b("k"), b("v"));
		assertThat(this.store.delete(b("k"))).isTrue();
		assertThat(this.listener.deleted).containsExactly("k");

		assertThat(this.store.delete(b("k"))).isFalse();
		assertThat(this.listener.deleted).containsExactly("k"); // still exactly once
	}

	@Test
	void deleteOfAbsentKeyFiresNothing() {
		assertThat(this.store.delete(b("absent"))).isFalse();
		assertThat(this.listener.deleted).isEmpty();
		assertThat(this.listener.expired).isEmpty();
	}

	// --- TTL: passive ------------------------------------------------------------------

	@Test
	void passiveExpiryOfPastDeadlineFiresExpiredExactlyOnce() {
		this.store.append(b("k"), b("v"));
		assertThat(this.store.expireAt(b("k"), this.clock.get() - 1)).isTrue();

		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.store.get(b("k"))).isNull();
		assertThat(this.listener.expired).containsExactly("k"); // second access must not
																// re-fire
	}

	@Test
	void passiveExpiryTriggersWhenClockReachesDeadline() {
		this.store.append(b("k"), b("v"));
		long deadline = this.clock.get() + 1000;
		assertThat(this.store.expireAt(b("k"), deadline)).isTrue();
		assertThat(this.store.exists(b("k"))).isTrue();
		assertThat(this.listener.expired).isEmpty();

		this.clock.set(deadline);
		assertThat(this.store.exists(b("k"))).isFalse();
		assertThat(this.listener.expired).containsExactly("k");
	}

	@Test
	void persistCancelsPendingExpiry() {
		this.store.append(b("k"), b("v"));
		assertThat(this.store.expireAt(b("k"), this.clock.get() + 1000)).isTrue();
		assertThat(this.store.persist(b("k"))).isTrue();
		assertThat(this.store.getExpireAt(b("k"))).isNull();

		this.clock.addAndGet(5000); // well past the original deadline
		assertThat(this.store.exists(b("k"))).isTrue();
		assertThat(this.listener.expired).isEmpty();
	}

	@Test
	void persistReturnsFalseForAbsentOrNoTtlKey() {
		assertThat(this.store.persist(b("absent"))).isFalse();
		this.store.append(b("k"), b("v"));
		assertThat(this.store.persist(b("k"))).isFalse();
	}

	@Test
	void getExpireAtReflectsTtlLifecycle() {
		this.store.append(b("k"), b("v"));
		assertThat(this.store.getExpireAt(b("k"))).isNull();

		long deadline = this.clock.get() + 1000;
		this.store.expireAt(b("k"), deadline);
		assertThat(this.store.getExpireAt(b("k"))).isEqualTo(deadline);

		this.clock.set(deadline);
		assertThat(this.store.getExpireAt(b("k"))).isNull();
		assertThat(this.listener.expired).containsExactly("k");
	}

	@Test
	void expireAtOfAbsentKeyReturnsFalse() {
		assertThat(this.store.expireAt(b("absent"), this.clock.get() + 1000)).isFalse();
	}

	// --- RENAME ------------------------------------------------------------------------

	@Test
	void renameMovesValueAndTtlWithoutFiringEvents() {
		this.store.append(b("old"), b("v"));
		long deadline = this.clock.get() + 1000;
		this.store.expireAt(b("old"), deadline);

		assertThat(this.store.rename(b("old"), b("new"))).isTrue();
		assertThat(this.store.exists(b("old"))).isFalse();
		assertThat(asString(this.store.get(b("new"))).value()).containsExactly(b("v"));
		assertThat(this.store.getExpireAt(b("new"))).isEqualTo(deadline);
		assertThat(this.listener.deleted).isEmpty();
		assertThat(this.listener.expired).isEmpty();
	}

	@Test
	void renameOfMissingSourceReturnsFalseWithoutEvents() {
		assertThat(this.store.rename(b("absent"), b("new"))).isFalse();
		assertThat(this.listener.deleted).isEmpty();
		assertThat(this.listener.expired).isEmpty();
	}

	@Test
	void renameOfExpiredSourceFiresExpiredAndFails() {
		this.store.append(b("old"), b("v"));
		this.store.expireAt(b("old"), this.clock.get() - 1);

		assertThat(this.store.rename(b("old"), b("new"))).isFalse();
		assertThat(this.listener.expired).containsExactly("old");
		assertThat(this.store.exists(b("new"))).isFalse();
	}

	@Test
	void renameOverwritesLiveDestinationWithoutEvents() {
		this.store.append(b("old"), b("A"));
		this.store.append(b("dst"), b("B"));

		assertThat(this.store.rename(b("old"), b("dst"))).isTrue();
		assertThat(asString(this.store.get(b("dst"))).value()).containsExactly(b("A"));
		assertThat(this.listener.deleted).isEmpty();
		assertThat(this.listener.expired).isEmpty();
	}

	@Test
	void renameOverExpiredDestinationFiresExpiredOnce() {
		this.store.append(b("src"), b("A"));
		this.store.append(b("dst"), b("B"));
		// dst now carries a past deadline but is never accessed, so it is not yet
		// evicted.
		this.store.expireAt(b("dst"), this.clock.get() - 1);

		assertThat(this.store.rename(b("src"), b("dst"))).isTrue();
		// Redis lazily expires the destination (firing expired) before overwriting it.
		assertThat(this.listener.expired).containsExactly("dst");
		assertThat(this.listener.deleted).isEmpty();
		assertThat(asString(this.store.get(b("dst"))).value()).containsExactly(b("A"));
	}

	// --- get()/type
	// ---------------------------------------------------------------------

	@Test
	void getNeverThrowsForWrongTypeQuery() {
		this.store.hset(b("h"), Map.of(b("f"), b("v")));
		assertThat(this.store.get(b("h"))).isInstanceOf(HashValue.class);
	}

	// --- TTL: active sweeper (real clock) ----------------------------------------------

	@Test
	void activeSweeperExpiresUntouchedKey() throws Exception {
		InMemoryKeyValueStore active = InMemoryKeyValueStore.builder().sweepInterval(Duration.ofMillis(20)).build();
		RecordingListener events = new RecordingListener();
		active.addKeyEventListener(events);
		try {
			active.append(b("k"), b("v"));
			active.expireAt(b("k"), System.currentTimeMillis() - 1); // already past;
																		// never accessed
																		// again
			awaitUntil(() -> !events.expired.isEmpty(), Duration.ofSeconds(2));
			assertThat(events.expired).containsExactly("k");
		}
		finally {
			active.close();
		}
	}

	// --- concurrency -------------------------------------------------------------------

	@Test
	void concurrentMutationOfDisjointKeysLosesNothing() throws Exception {
		int threads = 64;
		int perThread = 100;
		try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int tid = t;
				futures.add(exec.submit(() -> {
					for (int i = 0; i < perThread; i++) {
						this.store.append(b("key-" + tid + "-" + i), b("v"));
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
		for (int t = 0; t < threads; t++) {
			for (int i = 0; i < perThread; i++) {
				assertThat(this.store.exists(b("key-" + t + "-" + i))).isTrue();
			}
		}
	}

	@Test
	void concurrentPassiveExpiryFiresEachKeyExactlyOnce() throws Exception {
		int keys = 200;
		for (int i = 0; i < keys; i++) {
			this.store.append(b("k" + i), b("v"));
			this.store.expireAt(b("k" + i), this.clock.get() - 1); // live now, deadline
																	// already past
		}
		try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>();
			for (int t = 0; t < 16; t++) {
				futures.add(exec.submit(() -> {
					for (int i = 0; i < keys; i++) {
						this.store.exists(b("k" + i));
					}
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
		List<String> expected = IntStream.range(0, keys).mapToObj(i -> "k" + i).toList();
		assertThat(this.listener.expired).containsExactlyInAnyOrderElementsOf(expected);
	}

	@Test
	void passiveAndActiveExpiryRaceFiresEachKeyExactlyOnce() throws Exception {
		// Sweeper enabled AND client threads touch the same expiring keys: pins the
		// exactly-once guarantee across both eviction sites (passive access + sweeper).
		int keys = 200;
		InMemoryKeyValueStore raced = InMemoryKeyValueStore.builder().sweepInterval(Duration.ofMillis(5)).build();
		RecordingListener events = new RecordingListener();
		raced.addKeyEventListener(events);
		try {
			long past = System.currentTimeMillis() - 1;
			for (int i = 0; i < keys; i++) {
				raced.append(b("k" + i), b("v"));
				raced.expireAt(b("k" + i), past);
			}
			try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
				List<Future<?>> futures = new ArrayList<>();
				for (int t = 0; t < 16; t++) {
					futures.add(exec.submit(() -> {
						for (int i = 0; i < keys; i++) {
							raced.exists(b("k" + i));
						}
					}));
				}
				for (Future<?> f : futures) {
					f.get();
				}
			}
			List<String> expected = IntStream.range(0, keys).mapToObj(i -> "k" + i).toList();
			awaitUntil(() -> events.expired.size() >= keys, Duration.ofSeconds(5));
			assertThat(events.expired).containsExactlyInAnyOrderElementsOf(expected);
		}
		finally {
			raced.close();
		}
	}

	// --- helpers -----------------------------------------------------------------------

	private static byte[] b(String s) {
		return s.getBytes(UTF_8);
	}

	private static Map<byte[], byte[]> linkedMap(byte[]... kv) {
		Map<byte[], byte[]> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put(kv[i], kv[i + 1]);
		}
		return m;
	}

	private static Map<byte[], Double> scoredMap(Object... memberAndScore) {
		Map<byte[], Double> m = new LinkedHashMap<>();
		for (int i = 0; i < memberAndScore.length; i += 2) {
			m.put((byte[]) memberAndScore[i], (Double) memberAndScore[i + 1]);
		}
		return m;
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

	private static ZSetValue asSortedSet(@Nullable RedisValue value) {
		assertThat(value).isInstanceOf(ZSetValue.class);
		return (ZSetValue) requireNonNull(value);
	}

	private static void awaitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within " + timeout);
			}
			Thread.sleep(5);
		}
	}

	private static final class RecordingListener implements KeyEventListener {

		private final Collection<String> expired = new CopyOnWriteArrayList<>();

		private final Collection<String> deleted = new CopyOnWriteArrayList<>();

		@Override
		public void onExpired(byte[] key) {
			this.expired.add(new String(key, UTF_8));
		}

		@Override
		public void onDeleted(byte[] key) {
			this.deleted.add(new String(key, UTF_8));
		}

	}

}
