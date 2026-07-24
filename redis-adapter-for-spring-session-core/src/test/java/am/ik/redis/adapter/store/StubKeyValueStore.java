package am.ik.redis.adapter.store;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A named {@link KeyValueStore} that refuses every data operation.
 *
 * <p>
 * The core module ships no backend, so tests that exercise the connection and command
 * plumbing hand the server this instead. Any data operation fails loudly, which keeps the
 * tests honest about which layers they actually cover, while the {@link #name()} makes it
 * observable which database a connection ended up on.
 */
public final class StubKeyValueStore implements KeyValueStore {

	private final String name;

	/**
	 * Creates a store.
	 * @param name a label identifying this store in assertions
	 */
	public StubKeyValueStore(String name) {
		this.name = name;
	}

	/**
	 * Returns the label of this store.
	 * @return the name given at construction
	 */
	public String name() {
		return this.name;
	}

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public @Nullable RedisValue get(byte[] key) {
		throw unsupported();
	}

	@Override
	public boolean exists(byte[] key) {
		throw unsupported();
	}

	@Override
	public int append(byte[] key, byte[] value) {
		throw unsupported();
	}

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		throw unsupported();
	}

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		throw unsupported();
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		throw unsupported();
	}

	@Override
	public boolean delete(byte[] key) {
		throw unsupported();
	}

	@Override
	public boolean rename(byte[] src, byte[] dst) {
		throw unsupported();
	}

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		throw unsupported();
	}

	@Override
	public boolean persist(byte[] key) {
		throw unsupported();
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		throw unsupported();
	}

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		throw unsupported();
	}

	@Override
	public void close() {
	}

	private UnsupportedOperationException unsupported() {
		return new UnsupportedOperationException("store '" + this.name + "' holds no data");
	}

}
