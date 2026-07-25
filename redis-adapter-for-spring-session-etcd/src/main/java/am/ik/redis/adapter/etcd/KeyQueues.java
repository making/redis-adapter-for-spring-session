package am.ik.redis.adapter.etcd;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.RedisValue;
import org.jspecify.annotations.Nullable;

/**
 * One queue per key: everything an adapter does to a key goes through it, and the
 * mutations that pile up while one of them is in flight are applied together.
 *
 * <h2>Why</h2> Compare-and-swap on a single key does not degrade gracefully. Spring
 * Session has one genuinely contended key — every session expiring in the same minute
 * adds itself to that minute's set — and when many callers read-modify-write it at once,
 * the work per successful write grows with the number of callers, until the retries stop
 * keeping up and a share of the writes is simply lost. Retrying is what makes contention
 * <em>correct</em>; it is not what makes it scale.
 *
 * <h2>What happens instead</h2> A caller hands over its mutation and then waits for the
 * key's lock. Whoever holds the lock takes everything queued, applies it in order to one
 * value read once, and writes the result in a single transaction. That is exactly what
 * serialized execution would have produced — Redis serializes these anyway — so N
 * concurrent writes cost one etcd write rather than N times however many attempts each of
 * them needed, and the cost per write <em>falls</em> as the contention rises instead of
 * growing with it.
 *
 * <p>
 * Conflicts <em>between</em> replicas are untouched and still settled by the same
 * compare-and-swap. This removes only the contention an adapter has with itself, which is
 * the contention that used to be the norm rather than the exception.
 *
 * <h2>What it guarantees</h2>
 *
 * <ul>
 * <li>A mutation is queued <em>before</em> its caller asks for the lock, so a batch that
 * is already being applied still picks it up rather than leaving it for a batch of its
 * own.</li>
 * <li>Nobody is passed over: the lock is fair, and since it is held for a whole etcd
 * round trip the hand-off costs nothing beside that.</li>
 * <li>A mutation that refuses the value it is given fails the caller that asked for it
 * and nobody else. A failure that sank the whole batch — an etcd that will not answer —
 * is told to every caller in it.</li>
 * <li>A key nobody is at has no queue. A session store touches millions of keys and would
 * otherwise keep a lock for every one of them for as long as it runs.</li>
 * </ul>
 */
final class KeyQueues {

	private final Applier applier;

	private final ConcurrentHashMap<ByteArrayKey, Batch> batches = new ConcurrentHashMap<>();

	KeyQueues(Applier applier) {
		this.applier = Objects.requireNonNull(applier, "applier");
	}

	/**
	 * Queues a mutation of one key, applies whatever is queued for that key when it is
	 * this caller's turn, and returns what this caller's own mutation decided.
	 * @param <T> what the operation returns
	 * @param key the key being mutated
	 * @param mutation what to do with the current value
	 * @return what the mutation returned
	 * @throws RuntimeException whatever the mutation threw, or whatever stopped the batch
	 * it was in from landing
	 */
	<T> T mutate(byte[] key, Mutation<T> mutation) {
		ByteArrayKey id = ByteArrayKey.of(key);
		Pending<T> pending = new Pending<>(mutation);
		Batch batch = acquire(id);
		try {
			// Queued before the lock is asked for, so a batch in flight takes it too.
			batch.queued.add(pending);
			batch.lock.lock();
			try {
				if (!pending.settled()) {
					// Nobody applied it while this caller waited, so this caller applies
					// it — and everything else that arrived in the meantime.
					applyQueued(key, batch);
				}
				return pending.result();
			}
			finally {
				batch.lock.unlock();
			}
		}
		finally {
			release(id);
		}
	}

	/**
	 * Runs an operation with a key to itself, so that it neither loses to nor makes
	 * anything else lose to it. This is for what cannot be batched — a deadline, a
	 * removal — and what it buys is that inside one adapter a key is touched by one
	 * caller at a time.
	 * @param <T> what the operation returns
	 * @param key the key the operation is about
	 * @param operation the operation
	 * @return what the operation returned
	 */
	<T> T exclusively(byte[] key, Supplier<T> operation) {
		ByteArrayKey id = ByteArrayKey.of(key);
		Batch batch = acquire(id);
		try {
			batch.lock.lock();
			try {
				return operation.get();
			}
			finally {
				batch.lock.unlock();
			}
		}
		finally {
			release(id);
		}
	}

	/**
	 * Applies everything queued for a key as one batch. The caller holds the key's lock,
	 * which is what makes it the only one draining the queue, and its own mutation is
	 * settled by the time this returns.
	 * @param key the key being mutated
	 * @param batch the key's queue
	 */
	private void applyQueued(byte[] key, Batch batch) {
		List<Pending<?>> round = new ArrayList<>();
		for (Pending<?> queued = batch.queued.poll(); queued != null; queued = batch.queued.poll()) {
			round.add(queued);
		}
		if (round.isEmpty()) {
			// Only the holder of the lock drains, and it queued before it asked for the
			// lock, so there is always something here. Asking the store for a key nobody
			// is mutating would be a round trip for nothing.
			return;
		}
		try {
			this.applier.apply(key, round);
			round.forEach(Pending::settle);
		}
		catch (RuntimeException e) {
			// Nothing landed, so everyone in the batch hears the same failure rather than
			// waiting for an answer that is not coming.
			round.forEach(queued -> queued.settle(e));
		}
	}

	/**
	 * Applies every mutation of a batch in turn to the value that was read, which is what
	 * makes a batch equal to the same mutations run one at a time.
	 * @param batch the mutations, oldest first
	 * @param value what is under the key, or {@code null} if it is absent
	 * @return what the batch left behind
	 */
	static Batched applyInOrder(List<Pending<?>> batch, @Nullable RedisValue value) {
		boolean changed = false;
		boolean vanished = false;
		for (Pending<?> pending : batch) {
			Outcome<?> outcome = pending.stage(value);
			if (outcome == null) {
				continue; // it refused the value, which is this caller's failure alone
			}
			RedisValue write = outcome.write();
			if (write != null) {
				value = write;
				changed = true;
			}
			else if (outcome.vanish()) {
				value = null;
				changed = true;
				vanished = true;
			}
		}
		return new Batched(value, changed, vanished);
	}

	/**
	 * How many keys someone is currently at. It is zero once every caller has left, which
	 * is what keeps a store that has touched millions of keys from holding a queue for
	 * each of them.
	 * @return the number of keys with a queue
	 */
	int queuedKeys() {
		return this.batches.size();
	}

	/**
	 * Takes a share in the queue of a key, creating it if this caller is the first there.
	 * @param key the key
	 * @return the key's queue
	 */
	private Batch acquire(ByteArrayKey key) {
		return Objects.requireNonNull(this.batches.compute(key, (id, existing) -> {
			Batch batch = (existing != null) ? existing : new Batch();
			batch.users++;
			return batch;
		}));
	}

	/**
	 * Gives up a share in the queue of a key, forgetting it when the last caller leaves.
	 * @param key the key
	 */
	private void release(ByteArrayKey key) {
		this.batches.compute(key, (id, batch) -> (batch == null || --batch.users == 0) ? null : batch);
	}

	/**
	 * What a batch of mutations left behind, once every one of them had the value.
	 *
	 * @param value what should now be under the key, or {@code null} if the batch leaves
	 * it absent
	 * @param changed whether anything in the batch decided to write at all
	 * @param vanished whether the key went part-way through, so that whatever a later
	 * mutation wrote is a new key rather than the one that was read
	 */
	record Batched(@Nullable RedisValue value, boolean changed, boolean vanished) {
	}

	/** Applies a whole batch of mutations to one key. */
	@FunctionalInterface
	interface Applier {

		/**
		 * Applies every mutation in the batch to the key, in the order the callers
		 * arrived, and stages what each of them decided.
		 * @param key the key every mutation in the batch is for
		 * @param batch the mutations, oldest first
		 */
		void apply(byte[] key, List<Pending<?>> batch);

	}

	/**
	 * One caller's mutation, and the answer it is waiting for.
	 *
	 * <p>
	 * Every field is read and written under the lock of the key the mutation is queued
	 * at, which is what carries the answer from whoever applied it to whoever asked for
	 * it.
	 *
	 * @param <T> what the operation returns
	 */
	static final class Pending<T> {

		private final Mutation<T> mutation;

		private @Nullable Outcome<T> outcome;

		private @Nullable RuntimeException failure;

		private boolean settled;

		Pending(Mutation<T> mutation) {
			this.mutation = Objects.requireNonNull(mutation, "mutation");
		}

		/**
		 * Applies this mutation to whatever the mutations before it in the batch left
		 * behind, remembering what it decided. It is called again for every attempt the
		 * batch makes, so what is remembered is always the last attempt's.
		 * @param current the value under the key at this point in the batch, or
		 * {@code null} if it is absent
		 * @return what it decided, or {@code null} if it refused the value — which fails
		 * this caller alone and leaves the rest of the batch to carry on
		 */
		@Nullable Outcome<T> stage(@Nullable RedisValue current) {
			try {
				this.outcome = this.mutation.apply(current);
				this.failure = null;
			}
			catch (RuntimeException e) {
				this.outcome = null;
				this.failure = e;
			}
			return this.outcome;
		}

		/** Declares that what was staged has landed. */
		void settle() {
			this.settled = true;
		}

		/**
		 * Declares that nothing landed.
		 * @param failure why not
		 */
		void settle(RuntimeException failure) {
			this.outcome = null;
			this.failure = failure;
			this.settled = true;
		}

		/**
		 * Returns whether this mutation has its answer.
		 * @return {@code true} once it has been applied or has failed
		 */
		boolean settled() {
			return this.settled;
		}

		/**
		 * Returns what this caller asked for.
		 * @return what the mutation decided
		 * @throws RuntimeException whatever the mutation threw, or whatever stopped its
		 * batch from landing
		 */
		T result() {
			RuntimeException failure = this.failure;
			if (failure != null) {
				throw failure;
			}
			Outcome<T> outcome = this.outcome;
			if (outcome == null) {
				throw new IllegalStateException("nothing was applied for a caller that was told there was");
			}
			return outcome.result();
		}

	}

	/** The callers at one key. */
	private static final class Batch {

		/**
		 * Fair, so that a caller which arrived while a long queue was being applied is
		 * not passed over. The lock is held for a whole etcd round trip, beside which the
		 * hand-off a fair lock costs is nothing.
		 */
		private final ReentrantLock lock = new ReentrantLock(true);

		private final Queue<Pending<?>> queued = new ConcurrentLinkedQueue<>();

		/** How many callers are at this key, guarded by the map this batch is in. */
		private int users;

	}

}
