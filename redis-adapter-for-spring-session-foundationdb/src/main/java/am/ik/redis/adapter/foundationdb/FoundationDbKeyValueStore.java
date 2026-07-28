package am.ik.redis.adapter.foundationdb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ValueTooLargeException;
import am.ik.redis.adapter.store.ZSetValue;
import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.KeySelector;
import com.apple.foundationdb.KeyValue;
import com.apple.foundationdb.MutationType;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.Transaction;
import com.apple.foundationdb.subspace.Subspace;
import com.apple.foundationdb.tuple.ByteArrayUtil;
import com.apple.foundationdb.tuple.Tuple;
import com.apple.foundationdb.tuple.Versionstamp;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link KeyValueStore} that keeps the sessions in a
 * <a href="https://www.foundationdb.org/">FoundationDB</a> cluster.
 *
 * <p>
 * This is the third shared backend: every adapter replica reads and writes the same
 * keyspace, sessions outlive every adapter, and a key one replica removes is announced to
 * the clients of all of them. {@code .docs/design/architecture.md} §13 is the design;
 * what follows names only what a reader of this class needs.
 *
 * <h2>What a key holds</h2> A <em>meta key</em> per Redis key carries the type, the
 * deadline and — for a string — the payload. A hash keeps <strong>one key per
 * field</strong> and a collection <strong>one key per member</strong>, under a
 * tuple-encoded prefix of the Redis key, so reading a value is one range read. The layout
 * is not an optimization: one FoundationDB value may not exceed 100,000 bytes, and a
 * Spring Session hash over that is entirely ordinary. It also means two replicas adding
 * different members to the same expirations bucket write different keys and <strong>do
 * not conflict at all</strong>.
 *
 * <h2>Atomicity</h2> Every operation is one transaction, and there is no
 * compare-and-swap, version counter or conditional write anywhere in this backend: a
 * transaction that read the meta key conflicts, by itself, with anything that wrote it,
 * and FoundationDB retries it. {@link #rename} is one transaction too, which is more than
 * the SPI asks for.
 *
 * <h2>Expiry</h2> FoundationDB has no TTL of any kind, so the expiry is entirely this
 * backend's. The deadline lives on the meta key and every read compares against it; a
 * read that finds an overdue key removes it, and the removal is what announces it. Keys
 * nobody touches are announced by a sweeper, elected one per database by a lease, working
 * from a deadline-ordered index that the same transaction as the deadline keeps in step.
 *
 * <h2>Key events</h2> A removal and its announcement are <strong>one
 * transaction</strong>, with the reason written into the entry, so there is no
 * {@code del}-versus-{@code expired} guesswork and there are no tombstones: a removal
 * that must announce nothing simply writes no entry. The entries form an append-only log
 * keyed by FoundationDB's own commit versionstamp — there is no range watch, so a log is
 * the only shape that reaches every replica — and each store follows it forward from a
 * cursor, waking on a watch of the counter every removal bumps. Because a versionstamp is
 * commit order rather than a clock, there is no cursor lag and clock skew does not enter
 * into event delivery.
 *
 * <h2>Clocks</h2> Deadlines are absolute milliseconds on the <em>adapter's</em> clock, so
 * replicas need their clocks roughly in step. Skew shows up as a key expiring that much
 * early or late, never as a lost session or a lost event.
 */
public final class FoundationDbKeyValueStore implements KeyValueStore {

	private static final Logger logger = LoggerFactory.getLogger(FoundationDbKeyValueStore.class);

	/** The most one FoundationDB value may weigh; the 100,001st byte is refused. */
	static final int MAX_VALUE_BYTES = 100_000;

	/** The most one FoundationDB key may weigh, the tuple encoding included. */
	static final int MAX_KEY_BYTES = 10_000;

	/**
	 * How many log entries one drain transaction reads, so that it stays inside its time.
	 */
	private static final int DRAIN_LIMIT = 500;

	/**
	 * How many overdue keys one sweep transaction removes. Each one is read, so the whole
	 * batch conflicts with a writer that touches any of them: small enough that a retry
	 * is cheap, large enough that a backlog drains in a few passes.
	 */
	private static final int SWEEP_BATCH = 50;

	/** How many log entries one trim transaction clears. */
	private static final int TRIM_BATCH = 1_000;

	/**
	 * {@code transaction_timed_out}, which is how a watch reaches the end of its life.
	 */
	private static final int TRANSACTION_TIMED_OUT = 1031;

	private static final String TYPE_STRING = "string";

	private static final String TYPE_HASH = "hash";

	private static final String TYPE_SET = "set";

	private static final String TYPE_ZSET = "zset";

	private static final byte[] EMPTY = new byte[0];

	/** The parameter of the atomic {@code ADD} that bumps the log counter. */
	private static final byte[] ONE = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1).array();

	private final Database database;

	private final String keyPrefix;

	private final int databaseIndex;

	/** {@code ("m", key)}: the type, the deadline, and a string's payload. */
	private final Subspace meta;

	/** {@code ("d", key, part)}: one key per hash field and per collection member. */
	private final Subspace children;

	/** {@code ("x", deadline, key)}: what the sweeper reads, in deadline order. */
	private final Subspace due;

	/** {@code ("e", versionstamp)}: the append-only key-event log. */
	private final Subspace log;

	/** The counter every announcement bumps, and the only key a store watches. */
	private final byte[] counterKey;

	/** How far the log has been trimmed, which is how a stale cursor finds out. */
	private final byte[] watermarkKey;

	/** The sweeper election. */
	private final byte[] leaseKey;

	private final Duration transactionTimeout;

	private final Duration watchTimeout;

	private final Duration sweepInterval;

	private final Duration logRetention;

	private final Duration retryDelay;

	private final int maxAttempts;

	private final LongSupplier clock;

	private final CopyOnWriteArrayList<KeyEventListener> listeners = new CopyOnWriteArrayList<>();

	/** Which holder this store is when it takes the sweeper lease. */
	private final String sweeperId = UUID.randomUUID().toString();

	/** The last log entry this store has fired, which is where the next drain starts. */
	private volatile @Nullable Versionstamp cursor;

	private volatile boolean closed;

	private volatile @Nullable Thread follower;

	private volatile @Nullable Thread sweeper;

	private FoundationDbKeyValueStore(Builder builder, Database database) {
		this.database = database;
		this.keyPrefix = builder.keyPrefix;
		this.databaseIndex = builder.databaseIndex;
		Subspace root = new Subspace(Tuple.from(builder.keyPrefix, builder.databaseIndex));
		this.meta = root.subspace(Tuple.from("m"));
		this.children = root.subspace(Tuple.from("d"));
		this.due = root.subspace(Tuple.from("x"));
		this.log = root.subspace(Tuple.from("e"));
		this.counterKey = root.pack(Tuple.from("c"));
		this.watermarkKey = root.pack(Tuple.from("t"));
		this.leaseKey = root.pack(Tuple.from("s"));
		this.transactionTimeout = builder.transactionTimeout;
		this.watchTimeout = builder.watchTimeout;
		this.sweepInterval = builder.sweepInterval;
		this.logRetention = builder.logRetention;
		this.retryDelay = builder.retryDelay;
		this.maxAttempts = builder.maxAttempts;
		this.clock = builder.clock;
	}

	/**
	 * Returns a builder.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	// --- reads -------------------------------------------------------------------------

	@Override
	public long currentTimeMillis() {
		return this.clock.getAsLong();
	}

	@Override
	public @Nullable RedisValue get(byte[] key) {
		return inTransaction("GET " + name(key), attempt -> {
			Meta meta = live(attempt, key);
			return (meta == null) ? null : read(attempt, key, meta);
		});
	}

	@Override
	public boolean exists(byte[] key) {
		return inTransaction("EXISTS " + name(key), attempt -> live(attempt, key) != null);
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		return inTransaction("PTTL " + name(key), attempt -> {
			Meta meta = live(attempt, key);
			return (meta == null) ? null : meta.expireAt();
		});
	}

	/**
	 * Reads a key's meta, honouring passive expiration: an overdue key is removed — which
	 * is what announces it, here and, through the log, on every replica — before it is
	 * reported absent.
	 * @param attempt the transaction in progress
	 * @param key the Redis key
	 * @return what the key holds, or {@code null} if it is absent or has expired
	 */
	private @Nullable Meta live(Attempt attempt, byte[] key) {
		Meta meta = readMeta(attempt, key);
		if (meta == null) {
			return null;
		}
		if (meta.isExpired(currentTimeMillis())) {
			removeAnnouncing(attempt, key, Removal.EXPIRED);
			return null;
		}
		return meta;
	}

	private @Nullable Meta readMeta(Attempt attempt, byte[] key) {
		byte[] value = attempt.transaction.get(this.meta.pack(Tuple.from(key))).join();
		return (value == null) ? null : Meta.decode(value);
	}

	/**
	 * Reads whatever a live key holds. A string travels in the meta key itself;
	 * everything else is one range read of the key's children, which arrive in tuple
	 * order — the order a range read is for.
	 * @param attempt the transaction in progress
	 * @param key the Redis key
	 * @param meta the key's meta, already known to be live
	 * @return the typed value
	 */
	private RedisValue read(Attempt attempt, byte[] key, Meta meta) {
		switch (meta.type()) {
			case TYPE_STRING -> {
				return new StringValue(meta.requiredValue());
			}
			case TYPE_HASH -> {
				Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
				for (KeyValue field : attempt.transaction.getRange(childRange(key))) {
					fields.put(ByteArrayKey.of(part(field)), field.getValue());
				}
				return new HashValue(fields);
			}
			case TYPE_SET -> {
				Set<ByteArrayKey> members = new LinkedHashSet<>();
				for (KeyValue member : attempt.transaction.getRange(childRange(key))) {
					members.add(ByteArrayKey.of(part(member)));
				}
				return new SetValue(members);
			}
			case TYPE_ZSET -> {
				Map<ByteArrayKey, Double> scores = new LinkedHashMap<>();
				for (KeyValue member : attempt.transaction.getRange(childRange(key))) {
					scores.put(ByteArrayKey.of(part(member)), Tuple.fromBytes(member.getValue()).getDouble(0));
				}
				return new ZSetValue(scores);
			}
			default -> throw new FoundationDbException("Key " + name(key) + " holds an unknown type " + meta.type()
					+ "; the keyspace was written by a different version of this backend");
		}
	}

	// --- string ------------------------------------------------------------------------

	@Override
	public void set(byte[] key, byte[] value, @Nullable Long expireAtMillis) {
		String what = "SET " + name(key);
		byte[] stored = value.clone();
		inTransaction(what, attempt -> {
			// An overdue key dies announced before the new value takes its place; a live
			// one is replaced in silence, whatever it held, as Redis replaces it.
			Meta meta = live(attempt, key);
			if (meta != null) {
				clearDue(attempt, key, meta);
			}
			// The children of what was here — and the strays of a key that crashed
			// mid-removal — would otherwise be read as part of a later value.
			attempt.transaction.clear(childRange(key));
			// The value, the deadline asked for and the index entry the sweeper finds it
			// by
			// commit together, so the key is never there under a deadline nobody knows
			// of.
			writeMeta(attempt, key, new Meta(TYPE_STRING, expireAtMillis, stored), what);
			if (expireAtMillis != null) {
				attempt.transaction.set(dueKey(key, expireAtMillis), EMPTY);
			}
			return true;
		});
	}

	@Override
	public int append(byte[] key, byte[] value) {
		String what = "APPEND " + name(key);
		return inTransaction(what, attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null) {
				// Strays of a key that crashed mid-removal are not this key's; a key that
				// is being created starts empty.
				attempt.transaction.clear(childRange(key));
				writeMeta(attempt, key, new Meta(TYPE_STRING, null, value.clone()), what);
				return value.length;
			}
			if (!TYPE_STRING.equals(meta.type())) {
				throw new TypeMismatchException("APPEND against a key that does not hold a string");
			}
			byte[] current = meta.requiredValue();
			byte[] combined = Arrays.copyOf(current, current.length + value.length);
			System.arraycopy(value, 0, combined, current.length, value.length);
			// The deadline travels with the value: appending to a session's shadow key
			// must not extend or forget when it dies.
			writeMeta(attempt, key, new Meta(TYPE_STRING, meta.expireAt(), combined), what);
			return combined.length;
		});
	}

	// --- hash --------------------------------------------------------------------------

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		String what = "HSET " + name(key);
		// Last-wins over the wire order, and distinct names are what the count is of.
		Map<ByteArrayKey, byte[]> named = new LinkedHashMap<>();
		for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
			named.put(ByteArrayKey.of(field.getKey()), field.getValue());
		}
		return inTransaction(what, attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null) {
				attempt.transaction.clear(childRange(key));
				writeMeta(attempt, key, new Meta(TYPE_HASH, null, null), what);
			}
			else if (!TYPE_HASH.equals(meta.type())) {
				throw new TypeMismatchException("HSET against a key that does not hold a hash");
			}
			// An existing key's meta is read and not written, which is what keeps two
			// requests writing different fields of one session from conflicting.
			int added = 0;
			for (Map.Entry<ByteArrayKey, byte[]> field : named.entrySet()) {
				byte[] childKey = childKey(key, field.getKey().asBytes());
				requireFits(what, childKey, field.getValue().length);
				if (attempt.transaction.get(childKey).join() == null) {
					added++;
				}
				attempt.transaction.set(childKey, field.getValue().clone());
			}
			return added;
		});
	}

	// --- set ---------------------------------------------------------------------------

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		return addMembers(key, distinct(members), null, TYPE_SET, "SADD");
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		return removeMembers(key, distinct(members), TYPE_SET, "SREM");
	}

	// --- sorted set --------------------------------------------------------------------

	@Override
	public int zadd(byte[] key, Map<byte[], Double> scoredMembers) {
		Map<ByteArrayKey, Double> scores = new LinkedHashMap<>();
		for (Map.Entry<byte[], Double> scored : scoredMembers.entrySet()) {
			scores.put(ByteArrayKey.of(scored.getKey()), scored.getValue());
		}
		return addMembers(key, new LinkedHashSet<>(scores.keySet()), scores, TYPE_ZSET, "ZADD");
	}

	@Override
	public int zrem(byte[] key, List<byte[]> members) {
		return removeMembers(key, distinct(members), TYPE_ZSET, "ZREM");
	}

	/**
	 * Adds members to a set or sorted set, creating the key if it is absent.
	 *
	 * <p>
	 * Each member is its own key, so two replicas adding different members conflict over
	 * nothing at all — which is the case that costs the etcd backend the most (§11.4) and
	 * the reason for this layout. The count of newly added members is read in the same
	 * transaction that writes them, so it is exact rather than approximate.
	 * @param key the Redis key
	 * @param members the distinct members, in arrival order
	 * @param scores the member scores for a sorted set, or {@code null} for a set
	 * @param type {@link #TYPE_SET} or {@link #TYPE_ZSET}
	 * @param operation the Redis command, for messages
	 * @return how many members were newly added
	 */
	private int addMembers(byte[] key, Set<ByteArrayKey> members, @Nullable Map<ByteArrayKey, Double> scores,
			String type, String operation) {
		String what = operation + " " + name(key);
		return inTransaction(what, attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null) {
				attempt.transaction.clear(childRange(key));
				writeMeta(attempt, key, new Meta(type, null, null), what);
			}
			else if (!type.equals(meta.type())) {
				throw new TypeMismatchException(operation + " against a key that does not hold a "
						+ (TYPE_SET.equals(type) ? "set" : "sorted set"));
			}
			int added = 0;
			for (ByteArrayKey member : members) {
				byte[] childKey = childKey(key, member.asBytes());
				byte[] value = (scores == null) ? EMPTY
						: Tuple.from(Objects.requireNonNull(scores.get(member), "score")).pack();
				requireFits(what, childKey, value.length);
				if (attempt.transaction.get(childKey).join() == null) {
					added++;
				}
				// A sorted-set member that is already there moves to its new score rather
				// than being added a second time, which is what the count leaves out.
				attempt.transaction.set(childKey, value);
			}
			return added;
		});
	}

	/**
	 * Removes members from a set or sorted set, removing the key — silently, as Redis
	 * does — when it empties.
	 * @param key the Redis key
	 * @param members the distinct members to remove
	 * @param type {@link #TYPE_SET} or {@link #TYPE_ZSET}
	 * @param operation the Redis command, for messages
	 * @return how many members were actually removed
	 */
	private int removeMembers(byte[] key, Set<ByteArrayKey> members, String type, String operation) {
		return inTransaction(operation + " " + name(key), attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null) {
				return 0;
			}
			if (!type.equals(meta.type())) {
				throw new TypeMismatchException(operation + " against a key that does not hold a "
						+ (TYPE_SET.equals(type) ? "set" : "sorted set"));
			}
			int removed = 0;
			for (ByteArrayKey member : members) {
				byte[] childKey = childKey(key, member.asBytes());
				if (attempt.transaction.get(childKey).join() != null) {
					attempt.transaction.clear(childKey);
					removed++;
				}
			}
			// Read-your-writes is on, so what is left is what is left after this
			// transaction rather than what was there when it started.
			if (removed > 0 && !attempt.transaction.getRange(childRange(key), 1).iterator().hasNext()) {
				// Redis removes an emptied collection, and announces nothing for it.
				clearKey(attempt, key, meta);
			}
			return removed;
		});
	}

	// --- generic key -------------------------------------------------------------------

	@Override
	public boolean delete(byte[] key) {
		return inTransaction("DEL " + name(key), attempt -> {
			Meta meta = readMeta(attempt, key);
			if (meta == null) {
				return false;
			}
			if (meta.isExpired(currentTimeMillis())) {
				// Redis announces the lazy expiry rather than the delete, and answers as
				// if
				// the key had already been gone.
				removeAnnouncing(attempt, key, Removal.EXPIRED);
				return false;
			}
			removeAnnouncing(attempt, key, Removal.DELETED);
			return true;
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The whole move — the destination's children and meta written, the source's cleared
	 * — is <strong>one transaction</strong>, which is more than the SPI asks for. No log
	 * entry is written anywhere, because a rename must not look like a delete; a stale
	 * destination is expired first, in the same transaction, so its death is announced
	 * before it is overwritten.
	 */
	@Override
	public boolean rename(byte[] source, byte[] destination) {
		if (Arrays.equals(source, destination)) {
			// Redis renames a key onto itself as a no-op; moving would clear what is
			// moving.
			return exists(source);
		}
		String what = "RENAME " + name(source);
		return inTransaction(what, attempt -> {
			Meta moving = readMeta(attempt, source);
			if (moving == null) {
				return false;
			}
			long now = currentTimeMillis();
			if (moving.isExpired(now)) {
				// Redis expires the source lazily and the rename then fails, so the
				// expiry
				// is announced rather than swallowed by the move.
				removeAnnouncing(attempt, source, Removal.EXPIRED);
				return false;
			}
			Meta overwritten = readMeta(attempt, destination);
			if (overwritten != null && overwritten.isExpired(now)) {
				// A session overwritten without a word is one the application never hears
				// has ended.
				removeAnnouncing(attempt, destination, Removal.EXPIRED);
			}
			else if (overwritten != null) {
				// Redis overwrites a live destination in silence, whatever it held.
				clearKey(attempt, destination, overwritten);
			}
			// Whatever is still under the destination is a stray of an older key; a stale
			// field would otherwise read as part of the moved value.
			attempt.transaction.clear(childRange(destination));
			for (KeyValue child : attempt.transaction.getRange(childRange(source))) {
				attempt.transaction.set(childKey(destination, part(child)), child.getValue());
			}
			writeMeta(attempt, destination, moving, what);
			if (moving.expireAt() != null) {
				attempt.transaction.set(dueKey(destination, moving.expireAt()), EMPTY);
			}
			clearKey(attempt, source, moving);
			return true;
		});
	}

	// --- ttl ---------------------------------------------------------------------------

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		String what = "PEXPIREAT " + name(key);
		return inTransaction(what, attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null) {
				return false;
			}
			clearDue(attempt, key, meta);
			writeMeta(attempt, key, new Meta(meta.type(), epochMilli, meta.value()), what);
			// The index moves in the same transaction as the deadline, so it cannot drift
			// out of step with it - which is what lets the sweeper trust what it reads.
			attempt.transaction.set(dueKey(key, epochMilli), EMPTY);
			return true;
		});
	}

	@Override
	public boolean persist(byte[] key) {
		String what = "PERSIST " + name(key);
		return inTransaction(what, attempt -> {
			Meta meta = live(attempt, key);
			if (meta == null || meta.expireAt() == null) {
				return false;
			}
			clearDue(attempt, key, meta);
			writeMeta(attempt, key, new Meta(meta.type(), null, meta.value()), what);
			return true;
		});
	}

	// --- removal -----------------------------------------------------------------------

	/**
	 * Removes a key and announces it, in one transaction: the meta, the children and the
	 * deadline index go, and the key-event log gains an entry saying why. The two cannot
	 * part company, which is what makes the {@code del}-versus-{@code expired} question a
	 * field rather than a guess, and tombstones unnecessary.
	 * @param attempt the transaction in progress
	 * @param key the Redis key
	 * @param removal why, which is what every replica will report
	 */
	private void removeAnnouncing(Attempt attempt, byte[] key, Removal removal) {
		Meta meta = readMeta(attempt, key);
		if (meta == null) {
			return;
		}
		clearKey(attempt, key, meta);
		announce(attempt, key, removal);
	}

	/**
	 * Removes a key without announcing it, which is what a rename's source and an emptied
	 * collection need.
	 * @param attempt the transaction in progress
	 * @param key the Redis key
	 * @param meta the key's meta, for the deadline index entry it has to take with it
	 */
	private void clearKey(Attempt attempt, byte[] key, Meta meta) {
		attempt.transaction.clear(this.meta.pack(Tuple.from(key)));
		attempt.transaction.clear(childRange(key));
		clearDue(attempt, key, meta);
	}

	private void clearDue(Attempt attempt, byte[] key, Meta meta) {
		Long expireAt = meta.expireAt();
		if (expireAt != null) {
			attempt.transaction.clear(dueKey(key, expireAt));
		}
	}

	private void writeMeta(Attempt attempt, byte[] key, Meta meta, String what) {
		byte[] metaKey = this.meta.pack(Tuple.from(key));
		byte[] encoded = meta.encode();
		requireFits(what, metaKey, encoded.length);
		attempt.transaction.set(metaKey, encoded);
	}

	// --- events ------------------------------------------------------------------------

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * Appends one key-event log entry, in the transaction that made the removal.
	 *
	 * <p>
	 * The key carries FoundationDB's own commit versionstamp, so the log is in commit
	 * order by construction and a reader's cursor is a position in that order rather than
	 * a moment on somebody's clock. The counter is bumped with the atomic {@code ADD}
	 * mutation, which adds no conflict — it is a key every replica writes on every
	 * removal, and read-modify-write on it would make it the one contended key here.
	 * @param attempt the transaction in progress
	 * @param key the Redis key that went
	 * @param removal why it went
	 */
	private void announce(Attempt attempt, byte[] key, Removal removal) {
		byte[] entryKey = Tuple.from(Versionstamp.incomplete(attempt.nextUserVersion()))
			.packWithVersionstamp(this.log.pack());
		attempt.transaction.mutate(MutationType.SET_VERSIONSTAMPED_KEY, entryKey,
				Tuple.from(key, removal.reason, currentTimeMillis()).pack());
		attempt.transaction.mutate(MutationType.ADD, this.counterKey, ONE);
	}

	/**
	 * Follows the key-event log, turning every entry into the key event it records.
	 *
	 * <p>
	 * This is the whole of this backend's event delivery — this store's own removals
	 * included, so every replica hears every event in the same order. A watch on the
	 * counter is what wakes it, and it is established <em>before</em> the drain, or a
	 * removal committed between the two would wake nothing. The watch also carries the
	 * transaction's deadline, so it is renewed every {@code watchTimeout} whether
	 * anything happened or not: that is what keeps a cluster that went away from leaving
	 * a store waiting for ever, and it costs one empty transaction per interval rather
	 * than a poll.
	 */
	private void follow() {
		while (!this.closed) {
			CompletableFuture<Void> watch = null;
			try {
				watch = this.database.run(transaction -> {
					transaction.options().setTimeout(this.watchTimeout.toMillis());
					transaction.options().setRetryLimit(this.maxAttempts);
					return transaction.watch(this.counterKey);
				});
				drain();
				watch.get(this.watchTimeout.toMillis() + 1_000, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			catch (TimeoutException e) {
				// The watch outlived the deadline of the transaction that made it without
				// firing. Nothing was removed; the next pass renews it.
			}
			catch (ExecutionException | RuntimeException e) {
				if (this.closed) {
					break;
				}
				FDBException fdb = FoundationDbClient.unwrap(e);
				if (fdb == null || fdb.getCode() != TRANSACTION_TIMED_OUT) {
					logger.warn("Following the key-event log of database {} failed; retrying in {}", this.databaseIndex,
							this.retryDelay, e);
					if (!sleep(this.retryDelay)) {
						break;
					}
				}
				// A watch that reached its transaction's deadline is the ordinary quiet
				// path, not a failure: nothing was removed for a whole interval.
			}
			finally {
				if (watch != null) {
					watch.cancel(false);
				}
			}
		}
		logger.debug("Stopped following the key-event log of database {}", this.databaseIndex);
	}

	/**
	 * Reads the log forward from the cursor and fires what it finds, a bounded batch at a
	 * time so that no one transaction outlives its five seconds.
	 */
	private void drain() {
		while (!this.closed) {
			Drained drained = inTransaction("Reading the key-event log", this::readLog);
			if (drained.lostFrom() != null) {
				logger.warn(
						"The key-event log of database {} was trimmed past {}, which this store had not read yet; "
								+ "the events in between are lost, so a session that died during them stays until "
								+ "something touches it. Widen redis-adapter.foundationdb.log-retention if this recurs",
						this.databaseIndex, drained.lostFrom());
			}
			for (Event event : drained.events()) {
				fire(event);
			}
			this.cursor = drained.cursor();
			if (drained.events().size() < DRAIN_LIMIT) {
				return;
			}
		}
	}

	private Drained readLog(Attempt attempt) {
		Versionstamp from = this.cursor;
		Versionstamp lostFrom = null;
		byte[] watermark = attempt.transaction.get(this.watermarkKey).join();
		if (watermark != null) {
			Versionstamp trimmed = Tuple.fromBytes(watermark).getVersionstamp(0);
			if (from == null || from.compareTo(trimmed) < 0) {
				// Entries this store had not read have been trimmed away. It cannot read
				// them and it must not read the log from the beginning either, so it says
				// what it lost and starts from where the log now does.
				lostFrom = from;
				from = trimmed;
			}
		}
		List<Event> events = new ArrayList<>();
		KeySelector begin = (from == null) ? KeySelector.firstGreaterOrEqual(this.log.range().begin)
				: KeySelector.firstGreaterThan(this.log.pack(Tuple.from(from)));
		for (KeyValue entry : attempt.transaction.getRange(begin, KeySelector.firstGreaterOrEqual(this.log.range().end),
				DRAIN_LIMIT)) {
			Tuple recorded = Tuple.fromBytes(entry.getValue());
			events.add(new Event(recorded.getBytes(0), Removal.EXPIRED.reason.equals(recorded.getString(1))));
			from = this.log.unpack(entry.getKey()).getVersionstamp(0);
		}
		return new Drained(from, events, lostFrom);
	}

	private void fire(Event event) {
		for (KeyEventListener listener : this.listeners) {
			try {
				if (event.expired()) {
					listener.onExpired(event.key().clone());
				}
				else {
					listener.onDeleted(event.key().clone());
				}
			}
			catch (RuntimeException e) {
				logger.warn("KeyEventListener threw for key {}", ByteArrayKey.of(event.key()), e);
			}
		}
	}

	/** One log entry, as a listener will hear it. */
	private record Event(byte[] key, boolean expired) {
	}

	/**
	 * What one drain transaction found.
	 *
	 * @param cursor where the log has now been read to, which is {@code null} only when
	 * the log has never held anything
	 * @param events the entries to fire, oldest first
	 * @param lostFrom the cursor a trim had already passed, or {@code null} if nothing
	 * was lost
	 */
	private record Drained(@Nullable Versionstamp cursor, List<Event> events, @Nullable Versionstamp lostFrom) {
	}

	// --- sweeper -----------------------------------------------------------------------

	/**
	 * Announces the keys nobody touches, and keeps the log from growing without bound.
	 *
	 * <p>
	 * One replica sweeps per database, elected by a lease; the others keep following the
	 * log, so they still hear what the holder announces. Losing the election costs
	 * nothing, a dead holder's lease lapses, and a live replica takes it.
	 */
	private void sweep() {
		while (!this.closed) {
			if (!sleep(this.sweepInterval)) {
				break;
			}
			try {
				if (!takeLease()) {
					continue;
				}
				sweepOverdue();
				trimLog();
			}
			catch (RuntimeException e) {
				if (!this.closed) {
					logger.warn("Sweeping database {} failed; retrying in {}", this.databaseIndex, this.sweepInterval,
							e);
				}
			}
		}
		releaseLease();
	}

	/**
	 * Takes or renews the sweeper lease. Where the DynamoDB backend needs a conditional
	 * put for this, here it is an ordinary transaction: reading the lease is what
	 * conflicts with another replica taking it.
	 * @return {@code true} if this store holds the lease
	 */
	private boolean takeLease() {
		long now = currentTimeMillis();
		long leaseMillis = Math.max(this.sweepInterval.toMillis() * 5, 5_000);
		return inTransaction("Taking the sweeper lease of database " + this.databaseIndex, attempt -> {
			byte[] held = attempt.transaction.get(this.leaseKey).join();
			if (held != null) {
				Tuple lease = Tuple.fromBytes(held);
				if (!this.sweeperId.equals(lease.getString(0)) && lease.getLong(1) > now) {
					return false;
				}
			}
			attempt.transaction.set(this.leaseKey, Tuple.from(this.sweeperId, now + leaseMillis).pack());
			return true;
		});
	}

	private void releaseLease() {
		try {
			inTransaction("Releasing the sweeper lease of database " + this.databaseIndex, attempt -> {
				byte[] held = attempt.transaction.get(this.leaseKey).join();
				if (held != null && this.sweeperId.equals(Tuple.fromBytes(held).getString(0))) {
					attempt.transaction.clear(this.leaseKey);
				}
				return null;
			});
		}
		catch (RuntimeException e) {
			// Somebody else holds it, or the cluster has gone; either way a lease left
			// behind lapses on its own.
			logger.debug("Could not release the sweeper lease of database {}", this.databaseIndex, e);
		}
	}

	/**
	 * Removes and announces the keys whose deadline has passed, working from the
	 * deadline-ordered index in bounded batches — bounded because five seconds is the
	 * whole life of a transaction, and a batch that is always too big would retry for
	 * ever.
	 */
	private void sweepOverdue() {
		while (!this.closed) {
			int swept = inTransaction("Sweeping database " + this.databaseIndex, attempt -> {
				long now = currentTimeMillis();
				int seen = 0;
				for (KeyValue nominee : attempt.transaction.getRange(this.due.range().begin,
						this.due.pack(Tuple.from(now + 1)), SWEEP_BATCH)) {
					byte[] key = this.due.unpack(nominee.getKey()).getBytes(1);
					Meta meta = readMeta(attempt, key);
					if (meta != null && meta.isExpired(now)) {
						removeAnnouncing(attempt, key, Removal.EXPIRED);
					}
					else {
						// The deadline moved or the key has gone; the index entry is a
						// leftover of a replica that crashed between the two writes.
						attempt.transaction.clear(nominee.getKey());
					}
					seen++;
				}
				return seen;
			});
			if (swept < SWEEP_BATCH) {
				return;
			}
		}
	}

	/**
	 * Clears the log entries every replica has long since read, and records how far it
	 * got so that a replica which had not can find out (§13.4).
	 *
	 * <p>
	 * The scan is a <strong>snapshot</strong> read: every removal in the cluster writes
	 * to this range, so a trim that took a read conflict on it would lose to any
	 * concurrent removal for ever. It is safe, because an entry written while the trim
	 * runs has a higher versionstamp than anything being cleared.
	 */
	private void trimLog() {
		inTransaction("Trimming the key-event log of database " + this.databaseIndex, attempt -> {
			long cutoff = currentTimeMillis() - this.logRetention.toMillis();
			byte[] lastKey = null;
			Versionstamp lastStamp = null;
			for (KeyValue entry : attempt.transaction.snapshot().getRange(this.log.range(), TRIM_BATCH)) {
				if (Tuple.fromBytes(entry.getValue()).getLong(2) > cutoff) {
					break;
				}
				lastKey = entry.getKey();
				lastStamp = this.log.unpack(entry.getKey()).getVersionstamp(0);
			}
			if (lastKey == null) {
				return null;
			}
			attempt.transaction.clear(this.log.range().begin, ByteArrayUtil.join(lastKey, new byte[] { 0 }));
			attempt.transaction.set(this.watermarkKey, Tuple.from(lastStamp).pack());
			return null;
		});
	}

	// --- transactions ------------------------------------------------------------------

	/**
	 * Runs one transaction, bounded.
	 *
	 * <p>
	 * The timeout and the retry limit are set before the body does anything, and both are
	 * persisted across FoundationDB's own retry reset — which is what makes them bound
	 * the whole {@code run} rather than each attempt inside it. Without them two things
	 * here are unbounded: a read against a cluster that is not there waits for ever, and
	 * a body that is always slower than five seconds fails {@code transaction_too_old},
	 * which is <em>retryable</em>, so it would be retried for ever.
	 *
	 * <p>
	 * The body may therefore run more than once, and must be a pure function of what it
	 * reads. Nothing it wrote survives a retry, the versionstamps of any log entries
	 * included.
	 * @param <T> what the operation returns
	 * @param what the operation, for messages
	 * @param body what to do with the transaction
	 * @return what the body returned
	 */
	private <T extends @Nullable Object> T inTransaction(String what, Function<Attempt, T> body) {
		try {
			return this.database.run(transaction -> {
				transaction.options().setTimeout(this.transactionTimeout.toMillis());
				transaction.options().setRetryLimit(this.maxAttempts);
				return body.apply(new Attempt(transaction));
			});
		}
		catch (RuntimeException e) {
			// Everything the body threw arrives wrapped, the two failures the SPI names
			// included; translate is what unwraps them again.
			throw FoundationDbClient.translate(what, e);
		}
	}

	/**
	 * One run of a transaction body, which is not necessarily the only one.
	 *
	 * <p>
	 * It carries the user version each log entry of this attempt is stamped with: a
	 * versionstamped key needs one incomplete stamp, and two entries in one transaction
	 * have to differ somewhere. It counts from zero on every attempt, because a retry
	 * starts from nothing.
	 */
	private static final class Attempt {

		private final Transaction transaction;

		private int announcements;

		private Attempt(Transaction transaction) {
			this.transaction = transaction;
		}

		private int nextUserVersion() {
			return this.announcements++;
		}

	}

	// --- keys --------------------------------------------------------------------------

	private byte[] childKey(byte[] key, byte[] part) {
		return this.children.pack(Tuple.from(key, part));
	}

	private Range childRange(byte[] key) {
		return this.children.subspace(Tuple.from(key)).range();
	}

	/**
	 * Returns the field name or member a child key carries. Tuple encoding is what makes
	 * this unambiguous: a Redis key and a hash field are both arbitrary bytes, so joining
	 * them with a separator would not be.
	 * @param child the child entry
	 * @return the field name or member bytes
	 */
	private byte[] part(KeyValue child) {
		return this.children.unpack(child.getKey()).getBytes(1);
	}

	private byte[] dueKey(byte[] key, long expireAt) {
		return this.due.pack(Tuple.from(expireAt, key));
	}

	/**
	 * Refuses what FoundationDB would refuse, before anything is sent, so that the
	 * message names what did not fit rather than repeating the driver's.
	 * @param what the operation, for the message
	 * @param key the key being written
	 * @param valueBytes how many bytes its value carries
	 */
	private static void requireFits(String what, byte[] key, int valueBytes) {
		if (key.length > MAX_KEY_BYTES) {
			throw new ValueTooLargeException(what + " needs a FoundationDB key of " + key.length + " bytes, past the "
					+ MAX_KEY_BYTES + "-byte ceiling");
		}
		if (valueBytes > MAX_VALUE_BYTES) {
			throw new ValueTooLargeException(what + " needs a FoundationDB value of " + valueBytes + " bytes, past the "
					+ MAX_VALUE_BYTES + "-byte ceiling");
		}
	}

	private static ByteArrayKey name(byte[] key) {
		return ByteArrayKey.of(key);
	}

	private static Set<ByteArrayKey> distinct(List<byte[]> members) {
		Set<ByteArrayKey> distinct = new LinkedHashSet<>();
		for (byte[] member : members) {
			distinct.add(ByteArrayKey.of(member));
		}
		return distinct;
	}

	private boolean sleep(Duration duration) {
		try {
			Thread.sleep(duration);
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// --- lifecycle ---------------------------------------------------------------------

	/**
	 * Checks that FoundationDB answers, by the same bounded read every operation makes. A
	 * cluster that is away fails when the transaction's deadline passes rather than
	 * leaving this waiting, which is the whole reason the deadline is there.
	 * @throws FoundationDbException if the cluster could not be reached or refused
	 */
	public void checkHealth() {
		inTransaction("Checking the health of database " + this.databaseIndex,
				attempt -> attempt.transaction.get(this.counterKey).join());
	}

	/**
	 * Returns the prefix every key of this store lives under, which together with the
	 * database index is what separates one keyspace from another.
	 * @return the key prefix
	 */
	public String keyPrefix() {
		return this.keyPrefix;
	}

	/**
	 * Returns the database this store serves.
	 * @return the database index
	 */
	public int databaseIndex() {
		return this.databaseIndex;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		// The threads are stopped and joined before the database goes: closing it under a
		// transaction in flight would be a native failure rather than an exception.
		stop(this.follower);
		stop(this.sweeper);
		this.database.close();
	}

	private static void stop(@Nullable Thread thread) {
		if (thread == null) {
			return;
		}
		thread.interrupt();
		try {
			thread.join(Duration.ofSeconds(5));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// --- the meta key ------------------------------------------------------------------

	/**
	 * What the meta key of a Redis key holds.
	 *
	 * <p>
	 * A string's payload travels here rather than in a child key, so reading one is a
	 * point read; a collection's payload is its children. There is deliberately no
	 * incarnation counter — FoundationDB's transactions are what guard a removal against
	 * a key replaced underneath it, and a counter would only be a second, weaker guard.
	 *
	 * @param type one of the four Redis types
	 * @param expireAt the absolute deadline in epoch milliseconds, or {@code null} for a
	 * key that does not expire
	 * @param value the string payload, or {@code null} for a collection
	 */
	private record Meta(String type, @Nullable Long expireAt, byte @Nullable [] value) {

		static Meta decode(byte[] encoded) {
			Tuple tuple = Tuple.fromBytes(encoded);
			return new Meta(tuple.getString(0), (Long) tuple.get(1), (byte[]) tuple.get(2));
		}

		byte[] encode() {
			return Tuple.from(this.type, this.expireAt, this.value).pack();
		}

		boolean isExpired(long now) {
			return this.expireAt != null && this.expireAt <= now;
		}

		byte[] requiredValue() {
			return Objects.requireNonNull(this.value, "a string's meta key holds its value").clone();
		}

	}

	/** Why a key was removed, which is what its log entry carries. */
	private enum Removal {

		EXPIRED("expired"), DELETED("del");

		private final String reason;

		Removal(String reason) {
			this.reason = reason;
		}

	}

	/**
	 * Builder for {@link FoundationDbKeyValueStore}.
	 *
	 * <p>
	 * The cluster file is what a deployment has to give — unlike every other backend
	 * here, FoundationDB is not reached by a URL: the client reads a cluster file and
	 * finds the coordinators from it. Everything else has a default that suits a session
	 * store.
	 */
	public static final class Builder {

		private @Nullable String clusterFile;

		private int apiVersion = 730;

		private String keyPrefix = "/redis-adapter/";

		private int databaseIndex;

		private Duration transactionTimeout = Duration.ofSeconds(5);

		private Duration watchTimeout = Duration.ofSeconds(5);

		private Duration sweepInterval = Duration.ofSeconds(1);

		private Duration logRetention = Duration.ofSeconds(60);

		private Duration retryDelay = Duration.ofSeconds(1);

		private int maxAttempts = 10;

		private boolean sweeperEnabled = true;

		private LongSupplier clock = System::currentTimeMillis;

		private Builder() {
		}

		/**
		 * Sets the cluster file that names the cluster's coordinators.
		 * @param clusterFile the path, or {@code null} for FoundationDB's own default
		 * location
		 * @return this builder
		 */
		public Builder clusterFile(@Nullable String clusterFile) {
			this.clusterFile = clusterFile;
			return this;
		}

		/**
		 * Sets the API version to speak. It may be selected only once in a JVM and every
		 * store in one server therefore shares it; it also has to be one the installed
		 * native client supports.
		 * @param apiVersion the API version, for example {@code 730} for FoundationDB 7.3
		 * @return this builder
		 */
		public Builder apiVersion(int apiVersion) {
			this.apiVersion = apiVersion;
			return this;
		}

		/**
		 * Sets the prefix every key of this store lives under. Together with the database
		 * index it is what makes this one keyspace of its own, so two deployments can
		 * share a cluster by taking different prefixes and a cluster used for other
		 * things is untouched outside them.
		 * @param keyPrefix the prefix
		 * @return this builder
		 */
		public Builder keyPrefix(String keyPrefix) {
			if (keyPrefix.isEmpty()) {
				throw new IllegalArgumentException("the FoundationDB key prefix must not be empty");
			}
			this.keyPrefix = keyPrefix;
			return this;
		}

		/**
		 * Sets the database this store serves. It is part of every key, which is what
		 * makes the databases independent keyspaces in one cluster.
		 * @param databaseIndex the database number, counting from {@code 0}
		 * @return this builder
		 */
		public Builder databaseIndex(int databaseIndex) {
			if (databaseIndex < 0) {
				throw new IllegalArgumentException("databaseIndex must not be negative: " + databaseIndex);
			}
			this.databaseIndex = databaseIndex;
			return this;
		}

		/**
		 * Sets how long one transaction may take. It bounds how long a Redis command can
		 * hang — a FoundationDB read against a cluster that is not there waits for ever
		 * otherwise — and it must stay under FoundationDB's own five-second transaction
		 * lifetime to be the thing that fires.
		 * @param transactionTimeout the per-transaction deadline
		 * @return this builder
		 */
		public Builder transactionTimeout(Duration transactionTimeout) {
			this.transactionTimeout = requirePositive("transactionTimeout", transactionTimeout);
			return this;
		}

		/**
		 * Sets how long one watch on the key-event counter lives before it is renewed.
		 * Events ordinarily arrive as soon as the watch fires; this is what bounds the
		 * wait when a watch is lost, and what keeps a store from waiting for ever on a
		 * cluster that went away.
		 * @param watchTimeout the watch lifetime
		 * @return this builder
		 */
		public Builder watchTimeout(Duration watchTimeout) {
			this.watchTimeout = requirePositive("watchTimeout", watchTimeout);
			return this;
		}

		/**
		 * Sets how long between sweeps for keys nobody touches, which is the longest an
		 * abandoned session can sit unannounced.
		 * @param sweepInterval the sweep interval
		 * @return this builder
		 */
		public Builder sweepInterval(Duration sweepInterval) {
			this.sweepInterval = requirePositive("sweepInterval", sweepInterval);
			return this;
		}

		/**
		 * Sets how long key-event log entries are kept before the sweeper trims them. A
		 * replica that is away for longer than this comes back to a log that has moved on
		 * without it and loses the events in between, so it is a correctness setting
		 * rather than a housekeeping one.
		 * @param logRetention the retention
		 * @return this builder
		 */
		public Builder logRetention(Duration logRetention) {
			this.logRetention = requirePositive("logRetention", logRetention);
			return this;
		}

		/**
		 * Sets how long to wait before following the key-event log again after it fails.
		 * @param retryDelay the delay between attempts
		 * @return this builder
		 */
		public Builder retryDelay(Duration retryDelay) {
			this.retryDelay = requirePositive("retryDelay", retryDelay);
			return this;
		}

		/**
		 * Sets how many times FoundationDB retries a transaction that conflicted before
		 * giving up.
		 * @param maxAttempts the number of attempts, at least one
		 * @return this builder
		 */
		public Builder maxAttempts(int maxAttempts) {
			if (maxAttempts < 1) {
				throw new IllegalArgumentException("maxAttempts must be at least 1: " + maxAttempts);
			}
			this.maxAttempts = maxAttempts;
			return this;
		}

		/**
		 * Sets whether this store runs the sweeper that announces the keys nobody touches
		 * and trims the log. Off is for tests that need to hold expiry still; a
		 * deployment leaves it on, and the lease elects one holder per database however
		 * many replicas run.
		 * @param sweeperEnabled whether to sweep
		 * @return this builder
		 */
		public Builder sweeperEnabled(boolean sweeperEnabled) {
			this.sweeperEnabled = sweeperEnabled;
			return this;
		}

		/**
		 * Sets the time source, in epoch milliseconds. Deadlines are absolute times on
		 * this clock; a test can move it to make expiry deterministic.
		 * @param clock the clock
		 * @return this builder
		 */
		public Builder clock(LongSupplier clock) {
			this.clock = Objects.requireNonNull(clock, "clock");
			return this;
		}

		private static Duration requirePositive(String name, Duration value) {
			if (value.isNegative() || value.isZero()) {
				throw new IllegalArgumentException(name + " must be positive: " + value);
			}
			return value;
		}

		/**
		 * Builds the store, opens the cluster and starts following the key-event log.
		 *
		 * <p>
		 * Opening makes no connection, so a cluster that is briefly away builds perfectly
		 * well and is discovered by the first operation — which is what a backend created
		 * while an application starts, whether or not it is the one selected, has to do.
		 * A cluster <em>file</em> that cannot be read is a different thing and fails
		 * here.
		 *
		 * <p>
		 * The cursor starts at the end of the log, read before the follower thread
		 * starts, so a restarted adapter does not replay every event still in it — and so
		 * that a removal between the two is not missed either.
		 * @return a new store
		 */
		public FoundationDbKeyValueStore build() {
			Database database = FoundationDbClient.open(this.clusterFile, this.apiVersion);
			FoundationDbKeyValueStore store;
			try {
				store = new FoundationDbKeyValueStore(this, database);
			}
			catch (RuntimeException e) {
				database.close();
				throw e;
			}
			try {
				store.cursor = store.inTransaction("Reading where the key-event log ends", attempt -> {
					for (KeyValue last : attempt.transaction.getRange(store.log.range(), 1, true)) {
						return store.log.unpack(last.getKey()).getVersionstamp(0);
					}
					byte[] watermark = attempt.transaction.get(store.watermarkKey).join();
					return (watermark == null) ? null : Tuple.fromBytes(watermark).getVersionstamp(0);
				});
			}
			catch (RuntimeException e) {
				// Refusing to start over this would take the server down with a cluster
				// that is briefly away. The follower starts from the beginning of
				// whatever
				// the log holds instead, which repeats events rather than losing them.
				logger.warn("Could not read where the key-event log of database {} ends; "
						+ "the events still in it may be reported again", store.databaseIndex, e);
			}
			Thread follower = Thread.ofVirtual()
				.name("fdb-kvs-log-" + store.keyPrefix + store.databaseIndex)
				.unstarted(store::follow);
			store.follower = follower;
			follower.start();
			if (this.sweeperEnabled) {
				Thread sweeper = Thread.ofVirtual()
					.name("fdb-kvs-sweeper-" + store.keyPrefix + store.databaseIndex)
					.unstarted(store::sweep);
				store.sweeper = sweeper;
				sweeper.start();
			}
			return store;
		}

	}

}
