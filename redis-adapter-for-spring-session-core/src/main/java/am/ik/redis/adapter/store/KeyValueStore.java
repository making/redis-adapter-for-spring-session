package am.ik.redis.adapter.store;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The single pluggable backend seam of the adapter.
 *
 * <p>
 * A backend stores typed values ({@link RedisValue}) under byte-array keys, tracks an
 * absolute per-key TTL, and emits key-removal events ({@link KeyEventListener}). This is
 * the entire contract a future backend must satisfy; it is intentionally minimal and
 * Redis-agnostic (no RESP concepts leak in). The command layer maps wire commands onto
 * these operations.
 *
 * <p>
 * One instance models one Redis database (one keyspace). Multi-database support is
 * layered above by holding one store per database index and prepending the index to
 * keyspace notification channel names; the store itself is unaware of database numbers.
 *
 * <h2>Expiration</h2> Every operation honours <strong>passive expiration</strong>:
 * touching a key whose TTL has elapsed removes it and fires
 * {@link KeyEventListener#onExpired} before treating it as absent. A backend may
 * additionally run <strong>active expiration</strong> so keys that are never touched
 * still fire {@code onExpired} in bounded time.
 *
 * <h2>Bytes</h2> All keys, field names, members and values are raw bytes. Keys, field
 * names and members are compared by value (see {@link ByteArrayKey}); payload bytes are
 * opaque and returned byte-for-byte.
 *
 * <h2>Thread-safety</h2> Implementations must be safe for concurrent use by many threads
 * (the server uses one virtual thread per connection). Mutations are individually atomic
 * per key.
 */
public interface KeyValueStore extends AutoCloseable {

	/**
	 * Returns the store's current time in epoch milliseconds. The command layer uses this
	 * same clock to convert relative TTL commands (for example {@code PEXPIRE}) to and
	 * from the absolute deadlines this SPI works in, so that expiry stays consistent and
	 * deterministic under test.
	 * @return the current time in epoch milliseconds
	 */
	long currentTimeMillis();

	/**
	 * Returns the typed value stored under {@code key}, honouring passive expiration.
	 * @param key the key bytes
	 * @return the stored value, or {@code null} if the key is absent or has expired
	 */
	@Nullable RedisValue get(byte[] key);

	/**
	 * Reports whether {@code key} currently exists, honouring passive expiration.
	 * @param key the key bytes
	 * @return {@code true} if the key exists and has not expired
	 */
	boolean exists(byte[] key);

	/**
	 * Stores {@code value} as a string under {@code key}, replacing whatever was there —
	 * of whatever type — and dropping any TTL it had, exactly as Redis's {@code SET}
	 * does.
	 *
	 * <p>
	 * Replacing a live value fires <strong>no</strong> key event: overwriting a key is
	 * not deleting it, and Spring Session's {@code del} / {@code expired} notifications
	 * must not be synthesized from a write. The one exception is the usual lazy
	 * expiration — a key whose deadline has passed fires {@code onExpired} and is gone
	 * before this value takes its place.
	 * @param key the key bytes
	 * @param value the bytes to store (may be empty)
	 */
	void set(byte[] key, byte[] value);

	/**
	 * Appends {@code value} to the string stored under {@code key}. If the key is absent
	 * it is created as a string equal to {@code value} (so appending an empty array
	 * materializes a zero-length string). Any existing TTL is preserved.
	 * @param key the key bytes
	 * @param value the bytes to append (may be empty)
	 * @return the length of the string after the append
	 * @throws TypeMismatchException if the key exists and does not hold a string
	 */
	int append(byte[] key, byte[] value);

	/**
	 * Sets each field of the hash stored under {@code key}, creating the hash if absent.
	 * Any existing TTL is preserved. The map is iterated in order and applied last-wins;
	 * the store never calls {@code get} on it (its {@code byte[]} keys use identity
	 * equality), so callers should pass an insertion-ordered map that mirrors the wire
	 * order.
	 * @param key the key bytes
	 * @param fields the field-name to field-value pairs to set
	 * @return the number of fields that did not previously exist
	 * @throws TypeMismatchException if the key exists and does not hold a hash
	 */
	int hset(byte[] key, Map<byte[], byte[]> fields);

	/**
	 * Adds the given members to the set stored under {@code key}, creating the set if
	 * absent. Members already present (by value) are ignored.
	 * @param key the key bytes
	 * @param members the members to add
	 * @return the number of members newly added
	 * @throws TypeMismatchException if the key exists and does not hold a set
	 */
	int sadd(byte[] key, List<byte[]> members);

	/**
	 * Removes the given members from the set stored under {@code key}. When the set
	 * becomes empty the key is removed (matching Redis), but this does not fire
	 * {@code onDeleted} — only {@link #delete(byte[])} does.
	 * @param key the key bytes
	 * @param members the members to remove
	 * @return the number of members actually removed
	 * @throws TypeMismatchException if the key exists and does not hold a set
	 */
	int srem(byte[] key, List<byte[]> members);

	/**
	 * Adds the given members to the sorted set stored under {@code key}, creating the
	 * sorted set if absent. A member that is already there (by value) moves to its new
	 * score instead of being added a second time. Any existing TTL is preserved. The map
	 * is iterated in order and applied last-wins; the store never calls {@code get} on it
	 * (its {@code byte[]} keys use identity equality), so callers should pass an
	 * insertion-ordered map that mirrors the wire order.
	 * @param key the key bytes
	 * @param scoredMembers the member-to-score pairs to add
	 * @return the number of members newly added, which does not count the ones that only
	 * moved
	 * @throws TypeMismatchException if the key exists and does not hold a sorted set
	 */
	int zadd(byte[] key, Map<byte[], Double> scoredMembers);

	/**
	 * Removes the given members from the sorted set stored under {@code key}. When the
	 * sorted set becomes empty the key is removed (matching Redis), but this does not
	 * fire {@code onDeleted} — only {@link #delete(byte[])} does.
	 * @param key the key bytes
	 * @param members the members to remove
	 * @return the number of members actually removed
	 * @throws TypeMismatchException if the key exists and does not hold a sorted set
	 */
	int zrem(byte[] key, List<byte[]> members);

	/**
	 * Deletes {@code key}. If the key existed and had not expired this fires
	 * {@link KeyEventListener#onDeleted} and returns {@code true}. If the key had already
	 * expired this fires {@link KeyEventListener#onExpired} instead and returns
	 * {@code false}. Deleting an absent key is a no-op that fires nothing.
	 * @param key the key bytes
	 * @return {@code true} if a live key was deleted
	 */
	boolean delete(byte[] key);

	/**
	 * Renames {@code src} to {@code dst}, moving the value and any TTL and overwriting
	 * {@code dst} if it exists. The move itself fires <strong>no</strong> key events —
	 * crucially no {@code del} for the moved source, matching Redis, whose {@code del} /
	 * {@code expired} keyevents Spring Session relies on and which a rename must not
	 * trigger. The two exceptions mirror Redis's lazy expiration: if {@code src} has
	 * expired its {@code onExpired} fires and the rename fails; if {@code dst} held a
	 * logically-expired entry it is lazily expired (firing {@code onExpired} for
	 * {@code dst}) before being overwritten.
	 *
	 * <p>
	 * Each of the two key operations is individually atomic, but the move is
	 * <strong>not</strong> globally atomic across the two keys: a concurrent reader may
	 * briefly observe {@code src} already removed while {@code dst} is not yet written.
	 * This is unobservable under Spring Session's usage, which renames to a freshly
	 * generated, unique destination id with no concurrent access to either key.
	 * @param src the source key bytes
	 * @param dst the destination key bytes
	 * @return {@code true} on success; {@code false} if {@code src} is absent or expired
	 * (the command layer maps {@code false} to an {@code ERR no such key} error)
	 */
	boolean rename(byte[] src, byte[] dst);

	/**
	 * Sets the absolute expiry of {@code key} to {@code epochMilli}. A deadline already
	 * in the past does not remove the key immediately; the key is evicted (firing
	 * {@code onExpired}) on the next access or by the active sweeper.
	 * @param key the key bytes
	 * @param epochMilli the absolute expiry in epoch milliseconds
	 * @return {@code true} if the key exists and the expiry was set; {@code false} if the
	 * key is absent or had already expired
	 */
	boolean expireAt(byte[] key, long epochMilli);

	/**
	 * Removes any TTL from {@code key}, so it no longer expires.
	 * @param key the key bytes
	 * @return {@code true} if a TTL was present and cleared; {@code false} if the key is
	 * absent, had already expired, or had no TTL
	 */
	boolean persist(byte[] key);

	/**
	 * Returns the absolute expiry of {@code key} in epoch milliseconds, honouring passive
	 * expiration.
	 * @param key the key bytes
	 * @return the absolute expiry, or {@code null} if the key is absent, has expired, or
	 * has no TTL
	 */
	@Nullable Long getExpireAt(byte[] key);

	/**
	 * Registers a listener for key-removal events. Listeners are notified in registration
	 * order.
	 * @param listener the listener to add
	 */
	void addKeyEventListener(KeyEventListener listener);

	/**
	 * Releases resources held by the store (for the in-memory backend, stops the active
	 * expiry sweeper). After close the store remains usable for reads and passive
	 * expiration, but active expiration no longer runs. Implementations must make this
	 * idempotent.
	 */
	@Override
	void close();

}
