package am.ik.redis.adapter.store;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

/**
 * A complete but deliberately simple {@link KeyValueStore} for tests.
 *
 * <p>
 * The core module ships no backend on purpose, so its tests cannot reach for the bundled
 * in-memory one — that lives in a module which depends on core. This fills the gap for
 * both kinds of test in core: the command tests, which need real storage semantics, and
 * the server tests, which need a backend to route connections to. Being a <em>second</em>
 * implementation of the SPI it also keeps the command layer honest: anything a handler
 * needs must be expressible through the SPI alone.
 *
 * <h2>Clock</h2> The clock does not tick on its own and there is no active-expiry
 * sweeper. A test moves time with {@link #currentTimeMillis(long)} and
 * {@link #advance(long)} and observes only passive expiry, which makes expiry
 * deterministic without sleeping. The clock is meant to be driven from the test thread.
 *
 * <h2>Thread-safety</h2> The server serves each connection on its own thread, so this has
 * to satisfy the SPI's thread-safety requirement rather than merely look like it does.
 * Each key maps to an immutable {@link Entry} and every mutation goes through
 * {@link ConcurrentHashMap#compute}, so operations on one key are atomic and never tear.
 * Key events are fired after the map operation returns, never from inside it. As in a
 * real backend, {@link #rename} is atomic per key but not across the two keys.
 */
public final class FakeKeyValueStore implements KeyValueStore {

	private final ConcurrentHashMap<ByteArrayKey, Entry> entries = new ConcurrentHashMap<>();

	private final CopyOnWriteArrayList<KeyEventListener> listeners = new CopyOnWriteArrayList<>();

	private volatile long now = 1_000_000_000L;

	/**
	 * An immutable entry: the stored value plus its absolute expiry, if any.
	 */
	private record Entry(RedisValue value, @Nullable Long expireAtMillis) {

		boolean isExpired(long now) {
			return this.expireAtMillis != null && this.expireAtMillis <= now;
		}
	}

	/**
	 * What a mutation left behind for the listeners, decided inside the map operation and
	 * fired once it has returned.
	 */
	private enum Event {

		NONE, EXPIRED, DELETED

	}

	@Override
	public long currentTimeMillis() {
		return this.now;
	}

	/**
	 * Sets the current time.
	 * @param now the time in epoch milliseconds
	 * @return this store
	 */
	public FakeKeyValueStore currentTimeMillis(long now) {
		this.now = now;
		return this;
	}

	/**
	 * Moves the clock forward.
	 * @param millis how far to advance
	 * @return this store
	 */
	public FakeKeyValueStore advance(long millis) {
		this.now += millis;
		return this;
	}

	@Override
	public @Nullable RedisValue get(byte[] key) {
		Entry entry = liveEntry(ByteArrayKey.of(key));
		return (entry == null) ? null : entry.value();
	}

	@Override
	public boolean exists(byte[] key) {
		return liveEntry(ByteArrayKey.of(key)) != null;
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		Entry entry = liveEntry(ByteArrayKey.of(key));
		return (entry == null) ? null : entry.expireAtMillis();
	}

	@Override
	public int append(byte[] key, byte[] value) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		int[] length = { 0 };
		this.entries.compute(k, (ignored, current) -> {
			Entry live = live(current, event);
			byte[] combined = (live == null) ? value.clone() : concat(string(live), value);
			length[0] = combined.length;
			return new Entry(new StringValue(combined), (live == null) ? null : live.expireAtMillis());
		});
		fire(k, event[0]);
		return length[0];
	}

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		int[] added = { 0 };
		this.entries.compute(k, (ignored, current) -> {
			Entry live = live(current, event);
			Map<ByteArrayKey, byte[]> merged = new LinkedHashMap<>();
			if (live != null) {
				merged.putAll(hash(live));
			}
			int count = 0;
			for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
				if (merged.put(ByteArrayKey.of(field.getKey()), field.getValue().clone()) == null) {
					count++;
				}
			}
			added[0] = count;
			return new Entry(new HashValue(merged), (live == null) ? null : live.expireAtMillis());
		});
		fire(k, event[0]);
		return added[0];
	}

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		int[] added = { 0 };
		this.entries.compute(k, (ignored, current) -> {
			Entry live = live(current, event);
			Set<ByteArrayKey> merged = new LinkedHashSet<>();
			if (live != null) {
				merged.addAll(set(live));
			}
			int count = 0;
			for (byte[] member : members) {
				if (merged.add(ByteArrayKey.of(member))) {
					count++;
				}
			}
			added[0] = count;
			return new Entry(new SetValue(merged), (live == null) ? null : live.expireAtMillis());
		});
		fire(k, event[0]);
		return added[0];
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		int[] removed = { 0 };
		this.entries.computeIfPresent(k, (ignored, current) -> {
			Entry live = live(current, event);
			if (live == null) {
				return null;
			}
			Set<ByteArrayKey> merged = new LinkedHashSet<>(set(live));
			int count = 0;
			for (byte[] member : members) {
				if (merged.remove(ByteArrayKey.of(member))) {
					count++;
				}
			}
			removed[0] = count;
			// Redis removes a set that has become empty; that is not a delete.
			return merged.isEmpty() ? null : new Entry(new SetValue(merged), live.expireAtMillis());
		});
		fire(k, event[0]);
		return removed[0];
	}

	@Override
	public boolean delete(byte[] key) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		this.entries.computeIfPresent(k, (ignored, current) -> {
			event[0] = current.isExpired(this.now) ? Event.EXPIRED : Event.DELETED;
			return null;
		});
		fire(k, event[0]);
		return event[0] == Event.DELETED;
	}

	@Override
	public boolean rename(byte[] src, byte[] dst) {
		ByteArrayKey source = ByteArrayKey.of(src);
		Event[] sourceEvent = { Event.NONE };
		// computeIfPresent hands back the new value, so the entry being moved is captured
		// here rather than returned; the source is removed either way.
		@Nullable Entry[] moving = { null };
		this.entries.computeIfPresent(source, (ignored, current) -> {
			moving[0] = live(current, sourceEvent);
			return null;
		});
		fire(source, sourceEvent[0]);
		Entry moved = moving[0];
		if (moved == null) {
			return false;
		}
		ByteArrayKey destination = ByteArrayKey.of(dst);
		Event[] destinationEvent = { Event.NONE };
		this.entries.compute(destination, (ignored, current) -> {
			// Redis lazily expires a stale destination before overwriting it.
			live(current, destinationEvent);
			return moved;
		});
		fire(destination, destinationEvent[0]);
		return true;
	}

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		boolean[] set = { false };
		this.entries.computeIfPresent(k, (ignored, current) -> {
			Entry live = live(current, event);
			if (live == null) {
				return null;
			}
			set[0] = true;
			return new Entry(live.value(), epochMilli);
		});
		fire(k, event[0]);
		return set[0];
	}

	@Override
	public boolean persist(byte[] key) {
		ByteArrayKey k = ByteArrayKey.of(key);
		Event[] event = { Event.NONE };
		boolean[] cleared = { false };
		this.entries.computeIfPresent(k, (ignored, current) -> {
			Entry live = live(current, event);
			if (live == null || live.expireAtMillis() == null) {
				return live;
			}
			cleared[0] = true;
			return new Entry(live.value(), null);
		});
		fire(k, event[0]);
		return cleared[0];
	}

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.listeners.add(listener);
	}

	@Override
	public void close() {
	}

	/**
	 * Returns the live entry under {@code key}, evicting it and firing its expiry if its
	 * deadline has passed. The fast path is a plain read; only an expired entry escalates
	 * to a {@code compute}.
	 */
	private @Nullable Entry liveEntry(ByteArrayKey key) {
		Entry entry = this.entries.get(key);
		if (entry == null || !entry.isExpired(this.now)) {
			return entry;
		}
		Event[] event = { Event.NONE };
		// The entry may have been refreshed between the read and here (a PERSIST, say),
		// so
		// the eviction is decided again under the map operation.
		Entry refreshed = this.entries.computeIfPresent(key, (ignored, current) -> live(current, event));
		fire(key, event[0]);
		return refreshed;
	}

	/**
	 * Inside a map operation, treats an entry whose deadline has passed as absent and
	 * records that its expiry must be fired once the operation has returned.
	 */
	private @Nullable Entry live(@Nullable Entry current, Event[] event) {
		if (current == null) {
			return null;
		}
		if (current.isExpired(this.now)) {
			event[0] = Event.EXPIRED;
			return null;
		}
		return current;
	}

	private void fire(ByteArrayKey key, Event event) {
		for (KeyEventListener listener : this.listeners) {
			switch (event) {
				case EXPIRED -> listener.onExpired(key.asBytes());
				case DELETED -> listener.onDeleted(key.asBytes());
				case NONE -> {
				}
			}
		}
	}

	private static byte[] string(Entry entry) {
		if (entry.value() instanceof StringValue string) {
			return string.value();
		}
		throw new TypeMismatchException("operation against a key that does not hold a string");
	}

	private static Map<ByteArrayKey, byte[]> hash(Entry entry) {
		if (entry.value() instanceof HashValue hash) {
			return hash.fields();
		}
		throw new TypeMismatchException("operation against a key that does not hold a hash");
	}

	private static Set<ByteArrayKey> set(Entry entry) {
		if (entry.value() instanceof SetValue set) {
			return set.members();
		}
		throw new TypeMismatchException("operation against a key that does not hold a set");
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] result = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

}
