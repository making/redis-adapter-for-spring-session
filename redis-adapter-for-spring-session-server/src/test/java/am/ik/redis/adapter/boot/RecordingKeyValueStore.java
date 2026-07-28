package am.ik.redis.adapter.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import org.jspecify.annotations.Nullable;

/**
 * The backend this module's own tests run on: it satisfies the SPI by doing the work
 * through the in-memory store, and it remembers the database it was created for and
 * whether it was closed.
 *
 * <p>
 * Those two things are what the backend seam promises and what nothing else can observe —
 * that each database gets a store of its own, and that a backend holding a resource is
 * given the chance to let go of it when the application shuts down.
 *
 * <p>
 * A server has to have a backend to serve anything, and this module deliberately ships
 * none, so the tests bring their own. It is also the shape a backend written outside this
 * project has: a store, and a factory that hands out one per database.
 */
public final class RecordingKeyValueStore implements KeyValueStore {

	/**
	 * How long an expired key nobody touches may sit there. Short, because tests wait for
	 * it.
	 */
	private static final Duration SWEEP_INTERVAL = Duration.ofMillis(50);

	private final KeyValueStore delegate;

	private final int databaseIndex;

	private final AtomicBoolean closed = new AtomicBoolean();

	public RecordingKeyValueStore(int databaseIndex) {
		this(databaseIndex, true);
	}

	/**
	 * Creates the store of one database.
	 * @param databaseIndex the database number it is created for
	 * @param sweeperEnabled whether keys whose time has passed are swept in the
	 * background, which a test turns off to prove a key dies of the access that touches
	 * it
	 */
	public RecordingKeyValueStore(int databaseIndex, boolean sweeperEnabled) {
		this.databaseIndex = databaseIndex;
		this.delegate = InMemoryKeyValueStore.builder()
			.sweeperEnabled(sweeperEnabled)
			.sweepInterval(SWEEP_INTERVAL)
			.build();
	}

	/**
	 * Returns the database this store was created for.
	 * @return the database number it was asked for
	 */
	public int databaseIndex() {
		return this.databaseIndex;
	}

	/**
	 * Reports whether this store has been closed.
	 * @return {@code true} once {@link #close()} has been called
	 */
	public boolean isClosed() {
		return this.closed.get();
	}

	@Override
	public void close() {
		this.closed.set(true);
		this.delegate.close();
	}

	@Override
	public long currentTimeMillis() {
		return this.delegate.currentTimeMillis();
	}

	@Override
	public @Nullable RedisValue get(byte[] key) {
		return this.delegate.get(key);
	}

	@Override
	public boolean exists(byte[] key) {
		return this.delegate.exists(key);
	}

	@Override
	public void set(byte[] key, byte[] value) {
		this.delegate.set(key, value);
	}

	@Override
	public int append(byte[] key, byte[] value) {
		return this.delegate.append(key, value);
	}

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		return this.delegate.hset(key, fields);
	}

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		return this.delegate.sadd(key, members);
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		return this.delegate.srem(key, members);
	}

	@Override
	public int zadd(byte[] key, Map<byte[], Double> scoredMembers) {
		return this.delegate.zadd(key, scoredMembers);
	}

	@Override
	public int zrem(byte[] key, List<byte[]> members) {
		return this.delegate.zrem(key, members);
	}

	@Override
	public boolean delete(byte[] key) {
		return this.delegate.delete(key);
	}

	@Override
	public boolean rename(byte[] src, byte[] dst) {
		return this.delegate.rename(src, dst);
	}

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		return this.delegate.expireAt(key, epochMilli);
	}

	@Override
	public boolean persist(byte[] key) {
		return this.delegate.persist(key);
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		return this.delegate.getExpireAt(key);
	}

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.delegate.addKeyEventListener(listener);
	}

}
