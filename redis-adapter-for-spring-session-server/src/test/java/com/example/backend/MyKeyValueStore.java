package com.example.backend;

import java.util.List;
import java.util.Map;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import org.jspecify.annotations.Nullable;

/**
 * Stands in for the store a backend author would write, so that the factory the README
 * shows compiles and can be driven by a real client.
 *
 * <p>
 * Every operation is forwarded to the bundled in-memory store, which already satisfies
 * the SPI, including passive and active expiration and the key events those fire. A store
 * of one's own would talk to whatever holds the data instead; nothing else about the
 * factory or its registration changes.
 */
final class MyKeyValueStore implements KeyValueStore {

	private final KeyValueStore delegate = InMemoryKeyValueStore.create();

	private final int databaseIndex;

	MyKeyValueStore(int databaseIndex) {
		this.databaseIndex = databaseIndex;
	}

	/**
	 * Returns the database this store holds the keys of.
	 * @return the database number it was created for
	 */
	int databaseIndex() {
		return this.databaseIndex;
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

	@Override
	public void close() {
		this.delegate.close();
	}

}
