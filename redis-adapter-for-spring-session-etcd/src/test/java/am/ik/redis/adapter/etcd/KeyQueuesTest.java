package am.ik.redis.adapter.etcd;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import am.ik.redis.adapter.etcd.KeyQueues.Batched;
import am.ik.redis.adapter.etcd.KeyQueues.Pending;
import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the callers of one key take turns at it.
 *
 * <p>
 * This is the part of the fix for a contended key that has nothing to do with etcd, and
 * it is tested without one: what has to hold — that a batch is really one batch, that a
 * caller is not passed over, that one caller's failure is not everybody's — is about the
 * queueing rather than about the store underneath, and a real etcd could only make those
 * assertions probabilistic. The store here is therefore a map, and every case that turns
 * on timing holds a batch open rather than hoping for one.
 */
class KeyQueuesTest {

	private static final byte[] KEY = b("spring:session:expirations:1753400000000");

	private final FakeStore store = new FakeStore();

	private final KeyQueues queues = new KeyQueues(this.store);

	private final Map<String, Object> answers = new ConcurrentHashMap<>();

	// --- batching ----------------------------------------------------------------------

	/**
	 * The whole point: what arrives while one batch is in flight is applied as the next
	 * batch, in one go, rather than as one read-modify-write each.
	 */
	@Test
	void mutationsThatArriveWhileOneIsBeingAppliedAreAppliedTogether() throws Exception {
		this.store.holdTheNextRound();
		Thread first = adding("first");
		this.store.awaitApplying();
		List<Thread> rest = List.of(adding("second"), adding("third"), adding("fourth"));
		awaitQueuedAtTheKey(rest);

		this.store.release();
		join(first);
		join(rest);

		assertThat(this.store.batchSizes()).containsExactly(1, 3);
		assertThat(members()).containsExactly(key("first"), key("second"), key("third"), key("fourth"));
		assertThat(this.answers).containsOnlyKeys("first", "second", "third", "fourth")
			.allSatisfy((member, answer) -> assertThat(answer).isEqualTo(1));
	}

	/**
	 * Hundreds of callers at one key is what used to lose writes. None may be lost, and
	 * the work must not grow with the number of callers: far fewer batches than callers
	 * is the whole of the improvement.
	 */
	@Test
	void everyCallerLandsWhenHundredsShareOneKey() throws Exception {
		int callers = 256;
		// A batch costs a round trip in the real store, and it is while one is in flight
		// that the next batch forms. Without something standing in for it every caller
		// would find the key free and the test would measure nothing.
		this.store.eachRoundTakes(Duration.ofMillis(1));
		List<Thread> adding = IntStream.range(0, callers).mapToObj(caller -> adding("m-" + caller)).toList();

		join(adding);

		assertThat(members()).hasSize(callers);
		assertThat(this.answers.values()).allSatisfy(answer -> assertThat(answer).isEqualTo(1));
		assertThat(this.store.rounds()).isLessThan(callers);
	}

	/**
	 * An operation that cannot be batched — a deadline, a removal — has the key to
	 * itself, so nothing it is about to read changes underneath it.
	 */
	@Test
	void nothingIsAppliedWhileACallerHasTheKeyToItself() throws Exception {
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread exclusive = started("exclusive", () -> this.queues.exclusively(KEY, () -> {
			holding.countDown();
			await(release);
			return "done";
		}));
		holding.await();
		List<Thread> adding = List.of(adding("a"), adding("b"));
		awaitQueuedAtTheKey(adding);

		assertThat(this.store.rounds()).isZero();

		release.countDown();
		join(exclusive);
		join(adding);

		assertThat(this.store.rounds()).isEqualTo(1);
		assertThat(members()).containsExactly(key("a"), key("b"));
	}

	/**
	 * A queue per key would be a leak in a session store, which touches a key per session
	 * and never the same one twice.
	 */
	@Test
	void aKeyNobodyIsAtHasNoQueue() {
		this.queues.mutate(KEY, add("a"));
		this.queues.exclusively(KEY, () -> "done");

		assertThat(this.queues.queuedKeys()).isZero();
	}

	// --- failures ----------------------------------------------------------------------

	/**
	 * A batch that could not be written landed nothing, so nobody in it may be left
	 * waiting for an answer that is not coming.
	 */
	@Test
	void everyCallerInABatchThatCouldNotLandHearsWhy() throws Exception {
		this.store.failWith(new EtcdException("no endpoint could be reached"));
		this.store.holdTheNextRound();
		Thread first = adding("first");
		this.store.awaitApplying();
		List<Thread> rest = List.of(adding("second"), adding("third"));
		awaitQueuedAtTheKey(rest);

		this.store.release();
		join(first);
		join(rest);

		assertThat(this.answers).hasSize(3)
			.allSatisfy((member, answer) -> assertThat(answer).isInstanceOf(EtcdException.class));
	}

	/**
	 * {@code APPEND} against a set is the caller's mistake, not the mistake of whoever
	 * happened to be queued beside it. A batch is a convenience of this backend's, and no
	 * application may be made to notice that it is in one.
	 */
	@Test
	void aMutationThatRefusesTheValueFailsOnlyItsOwnCaller() {
		Pending<Integer> refuses = pending(current -> {
			throw new TypeMismatchException("APPEND against a key that does not hold a string");
		});
		List<Pending<?>> batch = List.of(pending(add("a")), refuses, pending(add("b")));

		Batched batched = KeyQueues.applyInOrder(batch, null);

		assertThat(asSet(batched.value()).members()).containsExactly(key("a"), key("b"));
		assertThatThrownBy(refuses::result).isInstanceOf(TypeMismatchException.class);
	}

	// --- what a batch decides ----------------------------------------------------------

	/**
	 * Applying a batch has to leave behind what running the same mutations one at a time
	 * would have, which is only true if each of them sees what the one before it left.
	 */
	@Test
	void everyMutationSeesWhatTheOneBeforeItLeft() {
		List<Pending<?>> batch = List.of(pending(add("a")), pending(add("b")), pending(add("a")));

		Batched batched = KeyQueues.applyInOrder(batch, null);

		assertThat(batched.changed()).isTrue();
		assertThat(batched.vanished()).isFalse();
		assertThat(asSet(batched.value()).members()).containsExactly(key("a"), key("b"));
		assertThat(results(batch)).containsExactly(1, 1, 0);
	}

	@Test
	void aBatchThatEmptiesTheKeyLeavesNothingBehind() {
		List<Pending<?>> batch = List.of(pending(remove("a")));

		Batched batched = KeyQueues.applyInOrder(batch, set("a"));

		assertThat(batched.changed()).isTrue();
		assertThat(batched.vanished()).isTrue();
		assertThat(batched.value()).isNull();
	}

	/**
	 * A set that is emptied and filled again inside one batch is a new key, exactly as it
	 * would be had the two calls been a moment apart — so the deadline of the one that
	 * went must not be carried over to the one that arrived.
	 */
	@Test
	void aKeyEmptiedPartWayThroughABatchIsANewKeyAfterwards() {
		List<Pending<?>> batch = List.of(pending(remove("a")), pending(add("b")));

		Batched batched = KeyQueues.applyInOrder(batch, set("a"));

		assertThat(batched.vanished()).isTrue();
		assertThat(asSet(batched.value()).members()).containsExactly(key("b"));
	}

	@Test
	void aBatchInWhichNothingDecidedToWriteIsNotWritten() {
		List<Pending<?>> batch = List.of(pending(remove("a")));

		Batched batched = KeyQueues.applyInOrder(batch, null);

		assertThat(batched.changed()).isFalse();
		assertThat(batched.value()).isNull();
		assertThat(results(batch)).containsExactly(0);
	}

	// --- the mutations under test ------------------------------------------------------

	/** What {@code SADD} of one member does, which is what the contended key gets. */
	private static Mutation<Integer> add(String member) {
		return current -> {
			Set<ByteArrayKey> members = new LinkedHashSet<>();
			if (current != null) {
				members.addAll(asSet(current).members());
			}
			return Outcome.write(members.add(key(member)) ? 1 : 0, new SetValue(members));
		};
	}

	/** What {@code SREM} of one member does, including removing a set it emptied. */
	private static Mutation<Integer> remove(String member) {
		return current -> {
			if (current == null) {
				return Outcome.nothing(0);
			}
			Set<ByteArrayKey> members = new LinkedHashSet<>(asSet(current).members());
			int removed = members.remove(key(member)) ? 1 : 0;
			return members.isEmpty() ? Outcome.vanish(removed) : Outcome.write(removed, new SetValue(members));
		};
	}

	// --- the store underneath ----------------------------------------------------------

	/**
	 * What a batch is applied to here: a map, which is enough to tell whether the
	 * mutations of a batch all landed and in what order, and which cannot lose a write
	 * for a reason of its own.
	 */
	private static final class FakeStore implements KeyQueues.Applier {

		private final Map<ByteArrayKey, RedisValue> values = new ConcurrentHashMap<>();

		private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();

		private final AtomicBoolean hold = new AtomicBoolean();

		private final CountDownLatch applying = new CountDownLatch(1);

		private final CountDownLatch released = new CountDownLatch(1);

		private volatile Duration pause = Duration.ZERO;

		private volatile @Nullable RuntimeException failure;

		@Override
		public void apply(byte[] key, List<Pending<?>> batch) {
			this.batchSizes.add(batch.size());
			if (this.hold.compareAndSet(true, false)) {
				this.applying.countDown();
				await(this.released);
			}
			sleep(this.pause);
			RuntimeException failure = this.failure;
			if (failure != null) {
				throw failure;
			}
			ByteArrayKey id = ByteArrayKey.of(key);
			Batched batched = KeyQueues.applyInOrder(batch, this.values.get(id));
			if (!batched.changed()) {
				return;
			}
			RedisValue value = batched.value();
			if (value == null) {
				this.values.remove(id);
			}
			else {
				this.values.put(id, value);
			}
		}

		/** Holds the next batch open, so that what arrives behind it provably queues. */
		void holdTheNextRound() {
			this.hold.set(true);
		}

		void awaitApplying() throws InterruptedException {
			assertThat(this.applying.await(10, TimeUnit.SECONDS)).isTrue();
		}

		/** Stands in for the round trip a batch costs against a real store. */
		void eachRoundTakes(Duration pause) {
			this.pause = pause;
		}

		void release() {
			this.released.countDown();
		}

		void failWith(RuntimeException failure) {
			this.failure = failure;
		}

		int rounds() {
			return this.batchSizes.size();
		}

		List<Integer> batchSizes() {
			return List.copyOf(this.batchSizes);
		}

		@Nullable RedisValue get(byte[] key) {
			return this.values.get(ByteArrayKey.of(key));
		}

	}

	// --- plumbing ----------------------------------------------------------------------

	/**
	 * Starts a caller that adds one member to the key and remembers what it was told,
	 * whether that is an answer or a failure.
	 * @param member the member to add
	 * @return the thread, already running
	 */
	private Thread adding(String member) {
		return started("adding-" + member, () -> {
			try {
				this.answers.put(member, this.queues.mutate(KEY, add(member)));
			}
			catch (RuntimeException e) {
				this.answers.put(member, e);
			}
		});
	}

	private static Thread started(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.start();
		return thread;
	}

	/**
	 * Waits until every thread is parked at the key, which is what makes the batch behind
	 * a held-open one provably contain all of them rather than probably.
	 * @param threads the callers expected to be queued
	 */
	private static void awaitQueuedAtTheKey(List<Thread> threads) throws InterruptedException {
		for (Thread thread : threads) {
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (thread.getState() != Thread.State.WAITING) {
				if (System.nanoTime() > deadline) {
					throw new AssertionError(thread.getName() + " never queued at the key");
				}
				Thread.sleep(1);
			}
		}
	}

	private static void join(Thread thread) throws InterruptedException {
		thread.join(Duration.ofSeconds(30));
		assertThat(thread.isAlive()).as("%s never finished", thread.getName()).isFalse();
	}

	private static void join(List<Thread> threads) throws InterruptedException {
		for (Thread thread : threads) {
			join(thread);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private static void sleep(Duration duration) {
		if (duration.isZero()) {
			return;
		}
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private Set<ByteArrayKey> members() {
		return asSet(this.store.get(KEY)).members();
	}

	private static <T> Pending<T> pending(Mutation<T> mutation) {
		return new Pending<>(mutation);
	}

	private static List<Object> results(List<Pending<?>> batch) {
		List<Object> results = new ArrayList<>();
		batch.forEach(pending -> results.add(pending.result()));
		return results;
	}

	private static SetValue set(String... members) {
		Set<ByteArrayKey> keys = new LinkedHashSet<>();
		for (String member : members) {
			keys.add(key(member));
		}
		return new SetValue(keys);
	}

	private static SetValue asSet(@Nullable RedisValue value) {
		return (SetValue) requireNonNull(value, "no value under the key");
	}

	private static ByteArrayKey key(String text) {
		return ByteArrayKey.of(b(text));
	}

	private static byte[] b(String text) {
		return text.getBytes(UTF_8);
	}

}
