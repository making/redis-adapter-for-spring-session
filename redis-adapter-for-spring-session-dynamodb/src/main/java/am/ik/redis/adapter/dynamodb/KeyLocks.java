package am.ik.redis.adapter.dynamodb;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import am.ik.redis.adapter.store.ByteArrayKey;

/**
 * One lock per key: everything one adapter does to a key is done with the key to itself.
 *
 * <p>
 * This is the exclusive half of the etcd backend's {@code KeyQueues}, and the half this
 * backend needs. With one item per member there is no whole-value read-modify-write to
 * batch — a concurrent {@code SADD} is a concurrent {@code PutItem} of a different item —
 * so what serializing buys here is not throughput but <em>answers</em>: the counts
 * {@code SADD} and {@code HSET} promise are read before the write, and they are only
 * exact as far as the caller is serialized. Within one adapter this makes them exact;
 * across replicas they stay approximate, which is the trade recorded in
 * {@code .docs/design/architecture.md} §12.1.
 *
 * <p>
 * A key nobody is at holds no lock: the map entry is created by the first caller to
 * arrive and removed by the last one to leave, so a store that has touched millions of
 * keys does not keep a lock for each of them. The lock is fair, because it is held for
 * whole DynamoDB round trips, beside which the hand-off a fair lock costs is nothing.
 */
final class KeyLocks {

	private final ConcurrentHashMap<ByteArrayKey, Holder> locks = new ConcurrentHashMap<>();

	/**
	 * Runs an operation with the key to itself.
	 * @param <T> what the operation returns
	 * @param key the key the operation is about
	 * @param operation the operation
	 * @return what the operation returned
	 */
	<T> T exclusively(byte[] key, Supplier<T> operation) {
		ByteArrayKey id = ByteArrayKey.of(key);
		Holder holder = acquire(id);
		try {
			holder.lock.lock();
			try {
				return operation.get();
			}
			finally {
				holder.lock.unlock();
			}
		}
		finally {
			release(id);
		}
	}

	/**
	 * How many keys someone is currently at, which is zero once every caller has left.
	 * @return the number of keys with a lock
	 */
	int lockedKeys() {
		return this.locks.size();
	}

	private Holder acquire(ByteArrayKey key) {
		return Objects.requireNonNull(this.locks.compute(key, (id, existing) -> {
			Holder holder = (existing != null) ? existing : new Holder();
			holder.users++;
			return holder;
		}));
	}

	private void release(ByteArrayKey key) {
		this.locks.compute(key, (id, holder) -> (holder == null || --holder.users == 0) ? null : holder);
	}

	/** The callers at one key. */
	private static final class Holder {

		private final ReentrantLock lock = new ReentrantLock(true);

		/** How many callers are at this key, guarded by the map this holder is in. */
		private int users;

	}

}
