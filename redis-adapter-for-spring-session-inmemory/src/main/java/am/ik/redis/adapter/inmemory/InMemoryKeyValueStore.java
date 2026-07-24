package am.ik.redis.adapter.inmemory;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory reference {@link KeyValueStore} backed by a {@link ConcurrentHashMap}.
 *
 * <p>
 * This backend is inherently single-node (each instance owns its own map) and is
 * therefore the development, single-instance and test backend. It supports string / hash
 * / set values, absolute per-key TTL, passive and active expiration, and delete/expire
 * callbacks.
 *
 * <h2>Concurrency model</h2> Each key maps to an immutable {@link Entry} (value +
 * absolute expiry). Mutations replace the entry atomically via
 * {@link ConcurrentHashMap#compute} so concurrent connections never see a torn value.
 * Reads take the lock-free {@code get} path and only escalate to {@code compute} when an
 * expired entry must be evicted. Key-event callbacks are always invoked
 * <strong>after</strong> the map operation returns, never while holding a bin lock.
 *
 * <h2>Active expiration and the clock</h2> A background sweeper on a virtual thread
 * evicts expired keys that are never accessed. It sleeps on real wall-clock time, so a
 * custom {@link Builder#clock(LongSupplier) clock} (used to make passive-expiry tests
 * deterministic) is only compatible with the sweeper disabled; tests that exercise the
 * sweeper should use the real clock with a short interval.
 *
 * <p>
 * After {@link #close()} the sweeper stops but the store stays usable for reads and
 * passive expiry.
 */
public final class InMemoryKeyValueStore implements KeyValueStore {

	private static final Logger log = LoggerFactory.getLogger(InMemoryKeyValueStore.class);

	/** Sentinel expiry meaning "never expires". */
	private static final long NO_EXPIRY = Long.MAX_VALUE;

	private final ConcurrentHashMap<ByteArrayKey, Entry> map = new ConcurrentHashMap<>();

	private final CopyOnWriteArrayList<KeyEventListener> listeners = new CopyOnWriteArrayList<>();

	private final LongSupplier clock;

	private final Duration sweepInterval;

	private volatile boolean closed = false;

	private volatile @Nullable Thread sweeper;

	private InMemoryKeyValueStore(LongSupplier clock, Duration sweepInterval) {
		this.clock = clock;
		this.sweepInterval = sweepInterval;
	}

	/**
	 * Creates a store with defaults: the system clock and a 1-second active sweeper.
	 * @return a new store
	 */
	public static InMemoryKeyValueStore create() {
		return builder().build();
	}

	/**
	 * Returns a builder for customizing the clock and sweeper.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	// --- immutable per-key entry -------------------------------------------------------

	private record Entry(RedisValue value, long expireAtMillis) {

		boolean isExpired(long now) {
			return this.expireAtMillis <= now;
		}
	}

	// --- reads -------------------------------------------------------------------------

	@Override
	public long currentTimeMillis() {
		return this.clock.getAsLong();
	}

	@Override
	public @Nullable RedisValue get(byte[] key) {
		Entry e = liveEntry(ByteArrayKey.of(key));
		return (e == null) ? null : e.value();
	}

	@Override
	public boolean exists(byte[] key) {
		return liveEntry(ByteArrayKey.of(key)) != null;
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		Entry e = liveEntry(ByteArrayKey.of(key));
		if (e == null || e.expireAtMillis() == NO_EXPIRY) {
			return null;
		}
		return e.expireAtMillis();
	}

	/**
	 * Returns the live entry for {@code k}, or {@code null}. Fast path is a lock-free
	 * {@code get}; only an entry whose TTL has elapsed escalates to
	 * {@code computeIfPresent} to evict it (firing {@code onExpired} exactly once) while
	 * tolerating a concurrent refresh.
	 */
	private @Nullable Entry liveEntry(ByteArrayKey k) {
		Entry e = this.map.get(k);
		if (e == null) {
			return null;
		}
		long now = this.clock.getAsLong();
		if (!e.isExpired(now)) {
			return e;
		}
		boolean[] fired = { false };
		Entry[] live = { null };
		this.map.computeIfPresent(k, (kk, cur) -> {
			if (cur.isExpired(now)) {
				fired[0] = true;
				return null;
			}
			live[0] = cur; // refreshed between get and compute (e.g. PERSIST)
			return cur;
		});
		if (fired[0]) {
			fireExpired(k);
		}
		return live[0];
	}

	// --- string ------------------------------------------------------------------------

	@Override
	public int append(byte[] key, byte[] value) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		int[] length = { 0 };
		this.map.compute(k, (kk, cur) -> {
			cur = passiveExpire(cur, now, expired);
			if (cur == null) {
				byte[] fresh = value.clone();
				length[0] = fresh.length;
				return new Entry(new StringValue(fresh), NO_EXPIRY);
			}
			if (!(cur.value() instanceof StringValue sv)) {
				throw new TypeMismatchException("APPEND against a key that does not hold a string");
			}
			byte[] combined = concat(sv.value(), value);
			length[0] = combined.length;
			return new Entry(new StringValue(combined), cur.expireAtMillis());
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return length[0];
	}

	// --- hash --------------------------------------------------------------------------

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		int[] added = { 0 };
		this.map.compute(k, (kk, cur) -> {
			cur = passiveExpire(cur, now, expired);
			Map<ByteArrayKey, byte[]> merged = new LinkedHashMap<>();
			long deadline = NO_EXPIRY;
			if (cur != null) {
				if (!(cur.value() instanceof HashValue hv)) {
					throw new TypeMismatchException("HSET against a key that does not hold a hash");
				}
				merged.putAll(hv.fields());
				deadline = cur.expireAtMillis();
			}
			int count = 0;
			for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
				if (merged.put(ByteArrayKey.of(field.getKey()), field.getValue().clone()) == null) {
					count++;
				}
			}
			added[0] = count;
			return new Entry(new HashValue(merged), deadline);
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return added[0];
	}

	// --- set ---------------------------------------------------------------------------

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		int[] added = { 0 };
		this.map.compute(k, (kk, cur) -> {
			cur = passiveExpire(cur, now, expired);
			Set<ByteArrayKey> merged = new LinkedHashSet<>();
			long deadline = NO_EXPIRY;
			if (cur != null) {
				if (!(cur.value() instanceof SetValue sv)) {
					throw new TypeMismatchException("SADD against a key that does not hold a set");
				}
				merged.addAll(sv.members());
				deadline = cur.expireAtMillis();
			}
			int count = 0;
			for (byte[] member : members) {
				if (merged.add(ByteArrayKey.of(member))) {
					count++;
				}
			}
			added[0] = count;
			return new Entry(new SetValue(merged), deadline);
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return added[0];
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		int[] removed = { 0 };
		this.map.compute(k, (kk, cur) -> {
			if (cur == null) {
				return null;
			}
			if (cur.isExpired(now)) {
				expired[0] = true;
				return null;
			}
			if (!(cur.value() instanceof SetValue sv)) {
				throw new TypeMismatchException("SREM against a key that does not hold a set");
			}
			Set<ByteArrayKey> merged = new LinkedHashSet<>(sv.members());
			int count = 0;
			for (byte[] member : members) {
				if (merged.remove(ByteArrayKey.of(member))) {
					count++;
				}
			}
			removed[0] = count;
			if (merged.isEmpty()) {
				return null; // Redis removes an emptied set; no del event (see javadoc)
			}
			return new Entry(new SetValue(merged), cur.expireAtMillis());
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return removed[0];
	}

	// --- generic key -------------------------------------------------------------------

	@Override
	public boolean delete(byte[] key) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		boolean[] existed = { false };
		this.map.compute(k, (kk, cur) -> {
			if (cur == null) {
				return null;
			}
			if (cur.isExpired(now)) {
				expired[0] = true;
				return null;
			}
			existed[0] = true;
			return null;
		});
		if (expired[0]) {
			fireExpired(k);
		}
		else if (existed[0]) {
			fireDeleted(k);
		}
		return existed[0];
	}

	@Override
	public boolean rename(byte[] src, byte[] dst) {
		ByteArrayKey s = ByteArrayKey.of(src);
		ByteArrayKey d = ByteArrayKey.of(dst);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		Entry[] moved = { null };
		this.map.computeIfPresent(s, (kk, cur) -> {
			if (cur.isExpired(now)) {
				expired[0] = true;
				return null;
			}
			moved[0] = cur;
			return null; // remove the source; captured for the move
		});
		if (expired[0]) {
			fireExpired(s);
		}
		if (moved[0] == null) {
			return false;
		}
		Entry moving = moved[0];
		boolean[] destinationExpired = { false };
		this.map.compute(d, (kk, cur) -> {
			// Redis lazily expires a stale destination (firing expired) before
			// overwriting.
			if (cur != null && cur.isExpired(now)) {
				destinationExpired[0] = true;
			}
			return moving; // overwrite destination with the moved value + TTL
		});
		if (destinationExpired[0]) {
			fireExpired(d);
		}
		return true;
	}

	// --- ttl ---------------------------------------------------------------------------

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		boolean[] set = { false };
		this.map.computeIfPresent(k, (kk, cur) -> {
			if (cur.isExpired(now)) {
				expired[0] = true;
				return null;
			}
			set[0] = true;
			return new Entry(cur.value(), epochMilli);
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return set[0];
	}

	@Override
	public boolean persist(byte[] key) {
		ByteArrayKey k = ByteArrayKey.of(key);
		long now = this.clock.getAsLong();
		boolean[] expired = { false };
		boolean[] cleared = { false };
		this.map.computeIfPresent(k, (kk, cur) -> {
			if (cur.isExpired(now)) {
				expired[0] = true;
				return null;
			}
			if (cur.expireAtMillis() == NO_EXPIRY) {
				return cur;
			}
			cleared[0] = true;
			return new Entry(cur.value(), NO_EXPIRY);
		});
		if (expired[0]) {
			fireExpired(k);
		}
		return cleared[0];
	}

	// --- events ------------------------------------------------------------------------

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	private void fireExpired(ByteArrayKey key) {
		for (KeyEventListener listener : this.listeners) {
			try {
				listener.onExpired(key.asBytes());
			}
			catch (RuntimeException ex) {
				log.warn("KeyEventListener.onExpired threw for key {}", key, ex);
			}
		}
	}

	private void fireDeleted(ByteArrayKey key) {
		for (KeyEventListener listener : this.listeners) {
			try {
				listener.onDeleted(key.asBytes());
			}
			catch (RuntimeException ex) {
				log.warn("KeyEventListener.onDeleted threw for key {}", key, ex);
			}
		}
	}

	// --- active expiry -----------------------------------------------------------------

	private void startSweeper() {
		Thread thread = Thread.ofVirtual().name("kvs-active-expiry").unstarted(this::runSweeper);
		this.sweeper = thread;
		thread.start();
	}

	private void runSweeper() {
		while (!this.closed) {
			try {
				Thread.sleep(this.sweepInterval);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
			if (this.closed) {
				break;
			}
			try {
				sweepExpired();
			}
			catch (RuntimeException ex) {
				log.warn("active expiry sweep failed", ex);
			}
		}
	}

	private void sweepExpired() {
		long now = this.clock.getAsLong();
		for (ByteArrayKey k : this.map.keySet()) {
			boolean[] fired = { false };
			this.map.computeIfPresent(k, (kk, cur) -> {
				if (cur.isExpired(now)) {
					fired[0] = true;
					return null;
				}
				return cur;
			});
			if (fired[0]) {
				fireExpired(k);
			}
		}
	}

	@Override
	public void close() {
		this.closed = true;
		Thread thread = this.sweeper;
		if (thread != null) {
			thread.interrupt();
		}
	}

	// --- helpers -----------------------------------------------------------------------

	/**
	 * Within a {@code compute} lambda, treats an expired entry as absent, recording that
	 * an expiry event must be fired after the map operation returns.
	 */
	private static @Nullable Entry passiveExpire(@Nullable Entry cur, long now, boolean[] expired) {
		if (cur != null && cur.isExpired(now)) {
			expired[0] = true;
			return null;
		}
		return cur;
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] result = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	/**
	 * Builder for {@link InMemoryKeyValueStore}.
	 */
	public static final class Builder {

		private LongSupplier clock = System::currentTimeMillis;

		private Duration sweepInterval = Duration.ofSeconds(1);

		private boolean sweeperEnabled = true;

		private Builder() {
		}

		/**
		 * Sets the time source, in epoch milliseconds. Useful to make passive-expiry
		 * tests deterministic; incompatible with the active sweeper (disable it too).
		 * @param clock the clock
		 * @return this builder
		 */
		public Builder clock(LongSupplier clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
			return this;
		}

		/**
		 * Sets the active sweeper interval.
		 * @param sweepInterval the interval between sweeps
		 * @return this builder
		 */
		public Builder sweepInterval(Duration sweepInterval) {
			this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval");
			return this;
		}

		/**
		 * Enables or disables the background active-expiry sweeper. When disabled, only
		 * passive expiration occurs.
		 * @param sweeperEnabled whether to run the sweeper
		 * @return this builder
		 */
		public Builder sweeperEnabled(boolean sweeperEnabled) {
			this.sweeperEnabled = sweeperEnabled;
			return this;
		}

		/**
		 * Builds the store, starting the sweeper if enabled.
		 * @return a new store
		 */
		public InMemoryKeyValueStore build() {
			InMemoryKeyValueStore store = new InMemoryKeyValueStore(this.clock, this.sweepInterval);
			if (this.sweeperEnabled) {
				store.startSweeper();
			}
			return store;
		}

	}

}
