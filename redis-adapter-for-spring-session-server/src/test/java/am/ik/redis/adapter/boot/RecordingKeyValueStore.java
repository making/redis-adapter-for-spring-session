package am.ik.redis.adapter.boot;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import org.jspecify.annotations.Nullable;

/**
 * A backend of the kind a module outside this project would contribute: it satisfies the
 * SPI by doing the work through the bundled store, and it remembers the database it was
 * created for and whether it was closed.
 *
 * <p>
 * Those two things are what the backend seam promises and what nothing else can observe —
 * that each database gets a store of its own, and that a backend holding a resource is
 * given the chance to let go of it when the application shuts down.
 */
final class RecordingKeyValueStore implements KeyValueStore {

	private final KeyValueStore delegate = InMemoryKeyValueStore.create();

	private final int databaseIndex;

	private final AtomicBoolean closed = new AtomicBoolean();

	RecordingKeyValueStore(int databaseIndex) {
		this.databaseIndex = databaseIndex;
	}

	/**
	 * Returns the database this store was created for.
	 * @return the database number it was asked for
	 */
	int databaseIndex() {
		return this.databaseIndex;
	}

	/**
	 * Reports whether this store has been closed.
	 * @return {@code true} once {@link #close()} has been called
	 */
	boolean isClosed() {
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
