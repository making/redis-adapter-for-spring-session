package am.ik.redis.adapter.etcd;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

import javax.net.ssl.SSLContext;

import am.ik.redis.adapter.etcd.EtcdClient.Kv;
import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import am.ik.redis.adapter.store.ZSetValue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link KeyValueStore} that keeps the sessions in an
 * <a href="https://etcd.io">etcd</a> cluster.
 *
 * <p>
 * This is the backend that makes the adapter horizontally scalable. Every adapter replica
 * reads and writes the same etcd keyspace, and — the part that a shared map alone would
 * not give — every replica learns about a key another replica deleted or let expire, so a
 * client subscribed to replica A still gets the notification for a session replica B
 * removed. Sessions also survive a restart of every adapter, because nothing is kept in
 * the adapter.
 *
 * <h2>What a key holds</h2> One Redis key is one etcd key, under a prefix that separates
 * the databases ({@code <key-prefix><database>/<redis key>}), holding an
 * {@link Envelope}: the typed value and its absolute deadline. Redis keys are bytes and
 * etcd keys are bytes, so nothing is encoded or escaped on the way.
 *
 * <h2>Expiry</h2> A key with a deadline is attached to an etcd <strong>lease</strong>,
 * and the deadline is also written into the value:
 *
 * <ul>
 * <li>the <strong>lease</strong> removes the key in bounded time when nobody comes back
 * to it, which is what the in-memory backend needs a sweeper for. Leases are whole
 * seconds, so the lease is always rounded <em>up</em>: etcd never collects a key before
 * it is due;</li>
 * <li>the <strong>deadline in the value</strong> is exact to the millisecond and is what
 * every read compares against, so a key is gone the moment it should be even while etcd
 * is still a second away from collecting it. A read that finds an overdue key removes it,
 * and the removal is what announces the expiry.</li>
 * </ul>
 *
 * <p>
 * Pushing a deadline out <strong>renews</strong> the lease the key is already on whenever
 * that lease renews to the TTL the new deadline needs, and grants a new one only when it
 * does not. This is the difference between one raft write and three, on the operation
 * Spring Session issues three times per session save, so it is the difference the cost of
 * this backend mostly consists of. The TTL a lease was granted with travels in the key
 * (see {@link Envelope}) because the replica pushing the deadline out is not necessarily
 * the one that granted it.
 *
 * <h2>Key events</h2> Every event comes from a <strong>watch</strong> on the database's
 * prefix, never from the call that caused it, which is what carries an expiry across
 * replicas. etcd does not say why a key was removed, so this backend does: the watch asks
 * for what the key held, and the deadline in it separates an expiry from a delete.
 * Removals that Redis announces nothing for — a renamed key's source, an emptied set —
 * are made silent by removing a {@link Envelope#tombstone() tombstone} instead of a
 * value. Events therefore arrive a network round trip after the operation rather than
 * inside it, which is the same order a Redis client sees them in, since a notification
 * and a reply travel on different connections anyway.
 *
 * <h2>Atomicity</h2> Every operation that reads and then writes does so in one etcd
 * transaction, guarded by the revision the read returned, and retries when the guard
 * fails. Two replicas adding to the same set therefore cannot lose an update. The one
 * exception is {@link #rename}, which the SPI already allows to be non-atomic across its
 * two keys.
 *
 * <p>
 * Inside one adapter a key is touched by one caller at a time, and the mutations that
 * pile up behind that caller are applied together in a single transaction — see
 * {@link KeyQueues} for why. Compare-and-swap is therefore left to settle the conflicts
 * between replicas, which are the rare ones, rather than the conflicts an adapter has
 * with itself, which are not.
 *
 * <h2>Clocks</h2> Deadlines are absolute milliseconds on the <em>adapter's</em> clock, so
 * replicas need their clocks roughly in step — which an etcd cluster needs anyway. Skew
 * shows up as a key expiring that much early or late, never as a lost session.
 */
public final class EtcdKeyValueStore implements KeyValueStore {

	private static final Logger logger = LoggerFactory.getLogger(EtcdKeyValueStore.class);

	/**
	 * How long a tombstone is leased for. It only has to outlive the two round trips that
	 * write and remove it; the lease is there so that a tombstone left behind by a
	 * process that died mid-rename goes away on its own. etcd may round it up, which
	 * costs nothing.
	 */
	private static final long TOMBSTONE_TTL_SECONDS = 1;

	/** The longest a retry waits, which bounds how long a contended command can take. */
	private static final long MAX_BACKOFF_MILLIS = 50;

	private final EtcdClient client;

	private final byte[] prefix;

	private final byte[] prefixEnd;

	private final String prefixText;

	private final LongSupplier clock;

	private final int maxAttempts;

	private final Duration watchRetryDelay;

	private final CopyOnWriteArrayList<KeyEventListener> listeners = new CopyOnWriteArrayList<>();

	/** What keeps this adapter's own callers from competing for the same key. */
	private final KeyQueues queues = new KeyQueues(this::applyBatch);

	private volatile boolean closed;

	private volatile @Nullable Thread watcher;

	/**
	 * The revision the watch has seen everything up to, so that a reconnect resumes
	 * rather than starts over and misses what happened while it was away.
	 */
	private volatile long watchedRevision;

	private EtcdKeyValueStore(Builder builder) {
		this.prefixText = builder.keyPrefix;
		this.prefix = builder.keyPrefix.getBytes(StandardCharsets.UTF_8);
		this.prefixEnd = rangeEnd(this.prefix);
		this.clock = builder.clock;
		this.maxAttempts = builder.maxAttempts;
		this.watchRetryDelay = builder.watchRetryDelay;
		this.client = EtcdClient.builder()
			.endpoints(builder.endpoints)
			.connectTimeout(builder.connectTimeout)
			.requestTimeout(builder.requestTimeout)
			.credentials(builder.username, builder.password)
			.sslContext(builder.sslContext)
			.build();
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
		Live live = live(etcdKey(key));
		return (live == null) ? null : live.envelope().requiredValue();
	}

	@Override
	public boolean exists(byte[] key) {
		return live(etcdKey(key)) != null;
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		Live live = live(etcdKey(key));
		if (live == null || live.envelope().expireAtMillis() == Envelope.NO_EXPIRY) {
			return null;
		}
		return live.envelope().expireAtMillis();
	}

	/**
	 * Reads a key, honouring passive expiration.
	 *
	 * <p>
	 * A key whose deadline has passed is removed here rather than merely hidden, which is
	 * both what Redis does and what makes the expiry heard: the removal is what every
	 * replica's watch turns into an {@code expired} notification. A tombstone is already
	 * logically absent and is left for its lease to collect.
	 * @param etcdKey the prefixed key
	 * @return what the key holds, or {@code null} if it is absent, expired or a tombstone
	 */
	private @Nullable Live live(byte[] etcdKey) {
		Kv kv = this.client.get(etcdKey);
		if (kv == null) {
			return null;
		}
		Envelope envelope = Envelope.decode(kv.value());
		if (envelope.isTombstone()) {
			return null;
		}
		if (envelope.isExpired(currentTimeMillis())) {
			this.client.deleteIfUnchanged(etcdKey, kv.modRevision());
			return null;
		}
		return new Live(kv, envelope);
	}

	/** A key that exists and has not expired. */
	private record Live(Kv kv, Envelope envelope) {
	}

	// --- string ------------------------------------------------------------------------

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * This is the one write that does not go through the batching queue: it replaces the
	 * value rather than deriving one from what is there, so there is nothing for a batch
	 * to fold together, and it has to leave the key on no lease at all — which is a
	 * decision about the key's deadline, and those are taken with the key held.
	 */
	@Override
	public void set(byte[] key, byte[] value) {
		byte[] stored = value.clone();
		this.queues.exclusively(key, () -> {
			setHeld(key, stored);
			return true;
		});
	}

	private void setHeld(byte[] key, byte[] value) {
		byte[] etcdKey = etcdKey(key);
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			Kv kv = this.client.get(etcdKey);
			long revision = 0;
			long lease = EtcdClient.NO_LEASE;
			if (kv != null) {
				revision = kv.modRevision();
				Envelope envelope = Envelope.decode(kv.value());
				if (!envelope.isTombstone() && envelope.isExpired(currentTimeMillis())) {
					// Redis expires the key first and creates it anew second, so the
					// removal is a round trip of its own and every replica's watch sees
					// the
					// expiry before this value exists.
					this.client.deleteIfUnchanged(etcdKey, revision);
					continue;
				}
				// A tombstone reads as absent, but its revision still guards the write,
				// so
				// what replaces it cannot overwrite a value written in the meantime.
				lease = kv.lease();
			}
			EtcdClient.Write written = this.client.putIfUnchanged(etcdKey, revision,
					Envelope.of(new StringValue(value), Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode(),
					EtcdClient.NO_LEASE);
			if (written.written()) {
				// SET drops the deadline the key had, so the lease it was on now holds
				// nothing — and a lease outlives the key it was granted for by as much as
				// its remaining TTL.
				this.client.revokeLeaseQuietly(lease);
				return;
			}
			backOff(attempt);
		}
		throw contention("SET", key);
	}

	@Override
	public int append(byte[] key, byte[] value) {
		return update(key, current -> {
			if (current == null) {
				return Outcome.write(value.length, new StringValue(value.clone()));
			}
			if (!(current instanceof StringValue string)) {
				throw new TypeMismatchException("APPEND against a key that does not hold a string");
			}
			byte[] combined = Arrays.copyOf(string.value(), string.value().length + value.length);
			System.arraycopy(value, 0, combined, string.value().length, value.length);
			return Outcome.write(combined.length, new StringValue(combined));
		});
	}

	// --- hash --------------------------------------------------------------------------

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		return update(key, current -> {
			Map<ByteArrayKey, byte[]> merged = new LinkedHashMap<>();
			if (current != null) {
				if (!(current instanceof HashValue hash)) {
					throw new TypeMismatchException("HSET against a key that does not hold a hash");
				}
				merged.putAll(hash.fields());
			}
			int added = 0;
			for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
				if (merged.put(ByteArrayKey.of(field.getKey()), field.getValue().clone()) == null) {
					added++;
				}
			}
			return Outcome.write(added, new HashValue(merged));
		});
	}

	// --- set ---------------------------------------------------------------------------

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		return update(key, current -> {
			Set<ByteArrayKey> merged = new LinkedHashSet<>();
			if (current != null) {
				if (!(current instanceof SetValue set)) {
					throw new TypeMismatchException("SADD against a key that does not hold a set");
				}
				merged.addAll(set.members());
			}
			int added = 0;
			for (byte[] member : members) {
				if (merged.add(ByteArrayKey.of(member))) {
					added++;
				}
			}
			return Outcome.write(added, new SetValue(merged));
		});
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		return update(key, current -> {
			if (current == null) {
				return Outcome.nothing(0);
			}
			if (!(current instanceof SetValue set)) {
				throw new TypeMismatchException("SREM against a key that does not hold a set");
			}
			Set<ByteArrayKey> remaining = new LinkedHashSet<>(set.members());
			int removed = 0;
			for (byte[] member : members) {
				if (remaining.remove(ByteArrayKey.of(member))) {
					removed++;
				}
			}
			if (remaining.isEmpty()) {
				// Redis removes an emptied set, and does not announce it as a delete.
				return Outcome.vanish(removed);
			}
			return Outcome.write(removed, new SetValue(remaining));
		});
	}

	// --- sorted set --------------------------------------------------------------------

	@Override
	public int zadd(byte[] key, Map<byte[], Double> scoredMembers) {
		return update(key, current -> {
			Map<ByteArrayKey, Double> merged = new LinkedHashMap<>();
			if (current != null) {
				if (!(current instanceof ZSetValue zset)) {
					throw new TypeMismatchException("ZADD against a key that does not hold a sorted set");
				}
				merged.putAll(zset.scores());
			}
			int added = 0;
			for (Map.Entry<byte[], Double> scored : scoredMembers.entrySet()) {
				if (merged.put(ByteArrayKey.of(scored.getKey()), scored.getValue()) == null) {
					added++;
				}
			}
			return Outcome.write(added, new ZSetValue(merged));
		});
	}

	@Override
	public int zrem(byte[] key, List<byte[]> members) {
		return update(key, current -> {
			if (current == null) {
				return Outcome.nothing(0);
			}
			if (!(current instanceof ZSetValue zset)) {
				throw new TypeMismatchException("ZREM against a key that does not hold a sorted set");
			}
			Map<ByteArrayKey, Double> remaining = new LinkedHashMap<>(zset.scores());
			int removed = 0;
			for (byte[] member : members) {
				if (remaining.remove(ByteArrayKey.of(member)) != null) {
					removed++;
				}
			}
			if (remaining.isEmpty()) {
				// Redis removes an emptied sorted set, and does not announce it either.
				return Outcome.vanish(removed);
			}
			return Outcome.write(removed, new ZSetValue(remaining));
		});
	}

	// --- generic key -------------------------------------------------------------------

	@Override
	public boolean delete(byte[] key) {
		return this.queues.exclusively(key, () -> {
			Kv previous = this.client.deleteAndReturnPrevious(etcdKey(key));
			if (previous == null) {
				return false;
			}
			// The lease the key was on now holds nothing, and a lease outlives the key it
			// was granted for by as much as its remaining TTL.
			this.client.revokeLeaseQuietly(previous.lease());
			Envelope envelope = Envelope.decode(previous.value());
			// Whether this was a delete or a lazy expiry is decided from the same value
			// the watch decides it from, so the answer to the caller and the notification
			// agree.
			return envelope.removalEvent(currentTimeMillis()) == Envelope.Removal.DELETED;
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The source is held for the duration, which is what its own transaction is guarded
	 * on. The destination is not: Redis overwrites it whatever it held, so there is
	 * nothing there for this adapter's other callers to lose.
	 */
	@Override
	public boolean rename(byte[] source, byte[] destination) {
		return this.queues.exclusively(source, () -> renameHeld(source, destination));
	}

	private boolean renameHeld(byte[] source, byte[] destination) {
		byte[] etcdSource = etcdKey(source);
		byte[] etcdDestination = etcdKey(destination);
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			Kv moving = this.client.get(etcdSource);
			if (moving == null) {
				return false;
			}
			Envelope envelope = Envelope.decode(moving.value());
			if (envelope.isTombstone()) {
				return false;
			}
			if (envelope.isExpired(currentTimeMillis())) {
				// Redis expires the source lazily and the rename then fails, so the
				// expiry
				// is announced rather than swallowed by the move.
				this.client.deleteIfUnchanged(etcdSource, moving.modRevision());
				return false;
			}
			expireStaleDestination(etcdDestination);
			long tombstoneLease = this.client.grantLease(TOMBSTONE_TTL_SECONDS);
			long moved = this.client.moveIfUnchanged(etcdSource, moving.modRevision(), Envelope.tombstone().encode(),
					tombstoneLease, etcdDestination, moving.value(), moving.lease());
			if (moved == 0) {
				this.client.revokeLeaseQuietly(tombstoneLease);
				backOff(attempt);
				continue;
			}
			// The source now holds a tombstone, so it reads as absent everywhere and its
			// removal announces nothing. Removing it here rather than leaving it to the
			// lease is only tidiness; the lease is what makes it safe.
			removeTombstone(etcdSource, moved, tombstoneLease);
			return true;
		}
		throw contention("RENAME", source);
	}

	/**
	 * Lazily expires an overdue destination before it is overwritten, so that the expiry
	 * is announced. Redis does the same, and a session that is overwritten without a word
	 * is a session the application never hears has ended.
	 * @param etcdDestination the prefixed destination key
	 */
	private void expireStaleDestination(byte[] etcdDestination) {
		Kv overwritten = this.client.get(etcdDestination);
		if (overwritten == null) {
			return;
		}
		Envelope envelope = Envelope.decode(overwritten.value());
		if (!envelope.isTombstone() && envelope.isExpired(currentTimeMillis())) {
			this.client.deleteIfUnchanged(etcdDestination, overwritten.modRevision());
		}
	}

	// --- ttl ---------------------------------------------------------------------------

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		return this.queues.exclusively(key, () -> expireAtHeld(key, epochMilli));
	}

	private boolean expireAtHeld(byte[] key, long epochMilli) {
		byte[] etcdKey = etcdKey(key);
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			Live live = live(etcdKey);
			if (live == null) {
				return false;
			}
			long ttlSeconds = leaseSeconds(epochMilli);
			long renewed = renewedLease(live, ttlSeconds);
			long lease = (renewed != EtcdClient.NO_LEASE) ? renewed : this.client.grantLease(ttlSeconds);
			EtcdClient.Write written = this.client.putIfUnchanged(etcdKey, live.kv().modRevision(),
					live.envelope().withDeadline(epochMilli, ttlSeconds).encode(), lease);
			if (written.written()) {
				if (renewed == EtcdClient.NO_LEASE) {
					// The key is on the new lease, so the old one holds nothing.
					// Revoking it matters: Spring Session sets the expiry again on
					// every request, and a lease left behind each time would pile up
					// in etcd until it aged out.
					this.client.revokeLeaseQuietly(live.kv().lease());
				}
				return true;
			}
			if (renewed == EtcdClient.NO_LEASE) {
				this.client.revokeLeaseQuietly(lease);
			}
			backOff(attempt);
		}
		throw contention("PEXPIREAT", key);
	}

	/**
	 * Returns the key's own lease when renewing it covers the new deadline, which is what
	 * makes the ordinary case — the same TTL pushed out again — one raft write instead of
	 * three.
	 *
	 * <p>
	 * A lease renews to the TTL it was granted with, and the key says what that was
	 * ({@link Envelope#leaseRenewsTo}), because the replica asking did not necessarily
	 * grant it. Anything else falls back to granting: a key on no lease at all, a
	 * deadline needing a different TTL, or a lease etcd has already collected.
	 * @param live the key as it was read
	 * @param ttlSeconds the lease TTL the new deadline needs
	 * @return the lease to write the key back on, or {@link EtcdClient#NO_LEASE} if one
	 * has to be granted
	 */
	private long renewedLease(Live live, long ttlSeconds) {
		long lease = live.kv().lease();
		if (lease == EtcdClient.NO_LEASE || !live.envelope().leaseRenewsTo(ttlSeconds)) {
			return EtcdClient.NO_LEASE;
		}
		// Renewed before the value is written rather than after: a deadline that landed
		// on
		// a lease which then failed to renew would be a key collected before it is due,
		// while renewing for a write that does not land costs nothing at all.
		return this.client.keepAliveLease(lease) ? lease : EtcdClient.NO_LEASE;
	}

	@Override
	public boolean persist(byte[] key) {
		return this.queues.exclusively(key, () -> persistHeld(key));
	}

	private boolean persistHeld(byte[] key) {
		byte[] etcdKey = etcdKey(key);
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			Live live = live(etcdKey);
			if (live == null || live.envelope().expireAtMillis() == Envelope.NO_EXPIRY) {
				return false;
			}
			EtcdClient.Write written = this.client.putIfUnchanged(etcdKey, live.kv().modRevision(),
					live.envelope().withDeadline(Envelope.NO_EXPIRY, Envelope.NO_LEASE_TTL).encode(),
					EtcdClient.NO_LEASE);
			if (written.written()) {
				this.client.revokeLeaseQuietly(live.kv().lease());
				return true;
			}
			backOff(attempt);
		}
		throw contention("PERSIST", key);
	}

	/**
	 * Returns the lease long enough to cover a deadline. It is rounded up, and never
	 * shorter than a second, so that etcd collects a key after it is due rather than
	 * before: the exact moment is the deadline in the value, which every read honours.
	 * @param epochMilli the absolute deadline
	 * @return the lease TTL in seconds
	 */
	private long leaseSeconds(long epochMilli) {
		long remaining = epochMilli - currentTimeMillis();
		return Math.max(1, Math.ceilDiv(remaining, 1000));
	}

	// --- mutation ----------------------------------------------------------------------

	/**
	 * Applies a mutation to whatever is under a key, atomically.
	 *
	 * <p>
	 * The mutation is queued at the key rather than run here: whichever caller has the
	 * key applies everything queued at it in one transaction, so callers of this adapter
	 * do not compete for a key they could simply take turns at. See {@link KeyQueues}.
	 * @param <T> what the operation returns
	 * @param key the Redis key
	 * @param mutation what to do with the current value, which is {@code null} when the
	 * key is absent
	 * @return what the mutation returned
	 * @throws EtcdException if the key changes under it more often than this store
	 * retries
	 */
	private <T> T update(byte[] key, Mutation<T> mutation) {
		return this.queues.mutate(key, mutation);
	}

	/**
	 * Applies a batch of mutations to one key, atomically.
	 *
	 * <p>
	 * The value is read, every mutation in the batch decides in turn what should replace
	 * it, and the write only lands if nothing else changed the key in between; otherwise
	 * the whole batch is applied again to the new value. That is what makes two adapter
	 * replicas adding to one set safe, and it is the reason a mutation must be a pure
	 * function of what it is given.
	 *
	 * <p>
	 * Any deadline and lease the key already has are carried over, since none of these
	 * operations is about expiry — {@code HSET} on a session must not extend or forget
	 * when it dies. A batch that empties the key part-way through is the exception: what
	 * a later mutation writes is then a new key, which in Redis has neither the deadline
	 * nor anything else the old one had.
	 * @param key the Redis key
	 * @param batch the mutations queued at it, oldest first
	 * @throws EtcdException if the key changes under it more often than this store
	 * retries
	 */
	private void applyBatch(byte[] key, List<KeyQueues.Pending<?>> batch) {
		byte[] etcdKey = etcdKey(key);
		Kv kv = this.client.get(etcdKey);
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			long revision = 0;
			long lease = EtcdClient.NO_LEASE;
			long expireAtMillis = Envelope.NO_EXPIRY;
			long leaseTtlSeconds = Envelope.NO_LEASE_TTL;
			RedisValue current = null;
			if (kv != null) {
				revision = kv.modRevision();
				Envelope envelope = Envelope.decode(kv.value());
				if (!envelope.isTombstone()) {
					if (envelope.isExpired(currentTimeMillis())) {
						// Redis expires the key first and creates it anew second, so the
						// expiry is announced before this operation's value exists. The
						// removal is a round trip of its own, which is what makes it
						// visible to every replica's watch.
						kv = this.client.deleteIfUnchanged(etcdKey, revision) ? null : this.client.get(etcdKey);
						continue;
					}
					current = envelope.requiredValue();
					lease = kv.lease();
					expireAtMillis = envelope.expireAtMillis();
					leaseTtlSeconds = envelope.leaseTtlSeconds();
				}
				// A tombstone is an absent key whose revision still guards the write, so
				// what replaces it cannot overwrite a value written in the meantime.
			}
			KeyQueues.Batched batched = KeyQueues.applyInOrder(batch, current);
			if (!batched.changed()) {
				return;
			}
			if (batched.vanished()) {
				lease = EtcdClient.NO_LEASE;
				expireAtMillis = Envelope.NO_EXPIRY;
				leaseTtlSeconds = Envelope.NO_LEASE_TTL;
			}
			RedisValue write = batched.value();
			if (write != null) {
				EtcdClient.Write written = this.client.putIfUnchanged(etcdKey, revision,
						Envelope.of(write, expireAtMillis, leaseTtlSeconds).encode(), lease);
				if (written.written()) {
					return;
				}
				// The transaction that refused the write read the key back, so the next
				// attempt starts from what is there now rather than from another round
				// trip.
				kv = written.current();
				backOff(attempt);
				continue;
			}
			long tombstoneLease = this.client.grantLease(TOMBSTONE_TTL_SECONDS);
			EtcdClient.Write written = this.client.putIfUnchanged(etcdKey, revision, Envelope.tombstone().encode(),
					tombstoneLease);
			if (written.written()) {
				removeTombstone(etcdKey, written.revision(), tombstoneLease);
				return;
			}
			this.client.revokeLeaseQuietly(tombstoneLease);
			kv = written.current();
			backOff(attempt);
		}
		throw contention("A mutation of", key);
	}

	/**
	 * Waits a little, and not the same little as anyone else, before trying a contended
	 * key again.
	 *
	 * <p>
	 * Retrying immediately is what turns contention into a livelock: two writers that
	 * lose to each other come back at the same moment and lose again. Since the callers
	 * of one adapter queue at a key rather than compete for it, what is left here is a
	 * conflict with another replica, which is uncommon — but it is also the one kind of
	 * conflict no amount of queueing can remove.
	 * @param attempt which attempt just failed, counting from one
	 */
	private void backOff(int attempt) {
		long ceiling = Math.min(MAX_BACKOFF_MILLIS, 1L << Math.min(attempt, 6));
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EtcdException("Interrupted while waiting to retry a contended key", e);
		}
	}

	/**
	 * Removes a tombstone that has served its purpose.
	 *
	 * <p>
	 * A tombstone that something has written over in the meantime is left alone: whatever
	 * is there now is a live value, and it is no longer on the tombstone's lease, so
	 * revoking that lease takes nothing with it.
	 * @param etcdKey the prefixed key
	 * @param revision the revision the tombstone was written at
	 * @param lease the tombstone's lease
	 */
	private void removeTombstone(byte[] etcdKey, long revision, long lease) {
		this.client.deleteIfUnchanged(etcdKey, revision);
		this.client.revokeLeaseQuietly(lease);
	}

	private EtcdException contention(String operation, byte[] key) {
		return new EtcdException(operation + " " + ByteArrayKey.of(key) + " gave up after " + this.maxAttempts
				+ " attempts because the key kept changing underneath it");
	}

	// --- events ------------------------------------------------------------------------

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * Follows the database's prefix in etcd, turning every removal into the key event it
	 * was.
	 *
	 * <p>
	 * This is the whole of this backend's event delivery, and the reason it is a watch
	 * rather than a callback in the operation that removed the key: a session expires or
	 * is deleted on whichever replica happened to touch it, while the client waiting to
	 * hear about it is connected to another. A reconnect resumes from the revision after
	 * the last one seen, so events that happen while the connection is down are still
	 * delivered.
	 */
	private void watch() {
		while (!this.closed) {
			long from = (this.watchedRevision == 0) ? 0 : this.watchedRevision + 1;
			try (InputStream stream = this.client.watch(this.prefix, this.prefixEnd, from);
					BufferedReader responses = new BufferedReader(
							new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				logger.debug("Watching etcd under {} from revision {}", this.prefixText, from);
				String response;
				while (!this.closed && (response = responses.readLine()) != null) {
					consume(Json.parseObject(response));
				}
			}
			catch (IOException | RuntimeException e) {
				if (this.closed) {
					break;
				}
				logger.warn("The etcd watch on {} stopped; reconnecting in {}", this.prefixText, this.watchRetryDelay,
						e);
			}
			if (this.closed) {
				break;
			}
			try {
				Thread.sleep(this.watchRetryDelay);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		logger.debug("Stopped watching etcd under {}", this.prefixText);
	}

	/**
	 * Reads one watch response.
	 * @param response the response, as the gateway wrote it
	 * @throws EtcdException if etcd cancelled the watch, which reconnects it
	 */
	private void consume(Map<String, Object> response) {
		Map<String, Object> result = Json.object(response.get("result"));
		if (result == null) {
			// The gateway reports a failure in place of a result rather than beside one.
			throw new EtcdException("The etcd watch was refused: " + response);
		}
		if (Json.flag(result.get("canceled"))) {
			long compacted = Json.integer(result.get("compact_revision"), 0);
			if (compacted > 0) {
				// etcd has discarded the history this watch was resuming from. Starting
				// again from now is the only option, and what was in between is lost: a
				// session that died during it stays until something touches it.
				logger.warn("etcd compacted revision {} away, past the {} the watch on {} was resuming from; "
						+ "key events in between are lost", compacted, this.watchedRevision, this.prefixText);
				this.watchedRevision = 0;
			}
			String reason = Objects.requireNonNullElse(Json.text(result.get("cancel_reason")), "no reason given");
			if (this.client.forgetTokenIfRejected(reason)) {
				logger.debug("The watch on {} was cancelled because its authentication token had gone; "
						+ "the next attempt authenticates again", this.prefixText);
			}
			throw new EtcdException("etcd cancelled the watch on " + this.prefixText + ": " + reason);
		}
		List<Object> events = Json.array(result.get("events"));
		for (Object event : events) {
			Map<String, Object> members = Json.object(event);
			if (members != null) {
				dispatch(members);
			}
		}
		Map<String, Object> header = Json.object(result.get("header"));
		long revision = Json.integer((header == null) ? null : header.get("revision"), 0);
		if (revision > this.watchedRevision) {
			this.watchedRevision = revision;
		}
	}

	/**
	 * Turns one watch event into a key event, or into nothing.
	 * @param event the event, as the gateway wrote it
	 */
	private void dispatch(Map<String, Object> event) {
		if (!"DELETE".equals(Json.text(event.get("type")))) {
			return; // a write is not something Redis announces here
		}
		Map<String, Object> previous = Json.object(event.get("prev_kv"));
		Map<String, Object> removed = Json.object(event.get("kv"));
		byte[] etcdKey = Json.bytes((removed != null) ? removed.get("key") : null);
		if (etcdKey == null && previous != null) {
			etcdKey = Json.bytes(previous.get("key"));
		}
		if (etcdKey == null) {
			logger.debug("An etcd watch event named no key: {}", event);
			return;
		}
		byte[] key = redisKey(etcdKey);
		if (key == null) {
			return; // another database's key, or none of this adapter's business
		}
		byte[] value = Json.bytes((previous != null) ? previous.get("value") : null);
		if (value == null) {
			// Every watch asks for what the key held, so this should not happen.
			// Reporting
			// a delete is the safer guess of the two: an application told a live session
			// has gone starts a new one, while an expiry nobody hears about leaves a
			// session that never ends.
			logger.debug("An etcd watch event did not say what the removed key held: {}", event);
			fireDeleted(key);
			return;
		}
		Envelope.Removal removal = Envelope.decode(value).removalEvent(currentTimeMillis());
		if (removal == null) {
			return; // a tombstone: a renamed source or an emptied set, which say nothing
		}
		switch (removal) {
			case EXPIRED -> fireExpired(key);
			case DELETED -> fireDeleted(key);
		}
	}

	private void fireExpired(byte[] key) {
		for (KeyEventListener listener : this.listeners) {
			try {
				listener.onExpired(key.clone());
			}
			catch (RuntimeException e) {
				logger.warn("KeyEventListener.onExpired threw for key {}", ByteArrayKey.of(key), e);
			}
		}
	}

	private void fireDeleted(byte[] key) {
		for (KeyEventListener listener : this.listeners) {
			try {
				listener.onDeleted(key.clone());
			}
			catch (RuntimeException e) {
				logger.warn("KeyEventListener.onDeleted threw for key {}", ByteArrayKey.of(key), e);
			}
		}
	}

	// --- keys --------------------------------------------------------------------------

	private byte[] etcdKey(byte[] key) {
		byte[] etcdKey = Arrays.copyOf(this.prefix, this.prefix.length + key.length);
		System.arraycopy(key, 0, etcdKey, this.prefix.length, key.length);
		return etcdKey;
	}

	private byte @Nullable [] redisKey(byte[] etcdKey) {
		if (etcdKey.length < this.prefix.length) {
			return null;
		}
		for (int i = 0; i < this.prefix.length; i++) {
			if (etcdKey[i] != this.prefix[i]) {
				return null;
			}
		}
		return Arrays.copyOfRange(etcdKey, this.prefix.length, etcdKey.length);
	}

	/**
	 * Returns the key just past everything starting with {@code prefix}, which is how
	 * etcd is asked for a prefix.
	 * @param prefix the prefix
	 * @return the exclusive end of the range
	 */
	private static byte[] rangeEnd(byte[] prefix) {
		byte[] end = prefix.clone();
		for (int i = end.length - 1; i >= 0; i--) {
			if (end[i] != (byte) 0xFF) {
				end[i]++;
				return Arrays.copyOf(end, i + 1);
			}
		}
		// Every byte is 0xFF, so the range has no end: etcd reads a single zero byte that
		// way.
		return new byte[] { 0 };
	}

	/**
	 * Checks that etcd answers, by reading through the prefix this store lives under. It
	 * is the same round trip every operation makes, credentials and all, so an answer
	 * here means the store works.
	 * @throws EtcdException if no endpoint could be reached or etcd refused
	 */
	public void checkHealth() {
		this.client.get(this.prefix);
	}

	/**
	 * Returns the prefix every key of this store lives under, which is what separates one
	 * database from another.
	 * @return the key prefix
	 */
	public String keyPrefix() {
		return this.prefixText;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		Thread watcher = this.watcher;
		if (watcher != null) {
			watcher.interrupt();
		}
		this.client.close();
	}

	/**
	 * Builder for {@link EtcdKeyValueStore}.
	 *
	 * <p>
	 * The endpoints and the key prefix are what a deployment has to give; everything else
	 * has a default that suits a session store. The prefix is what separates one database
	 * (and one deployment) from another in a shared cluster, so two stores must never
	 * share one.
	 */
	public static final class Builder {

		private final List<String> endpoints = new ArrayList<>(List.of("http://localhost:2379"));

		private String keyPrefix = "/redis-adapter/0/";

		private Duration connectTimeout = Duration.ofSeconds(5);

		private Duration requestTimeout = Duration.ofSeconds(5);

		private Duration watchRetryDelay = Duration.ofSeconds(1);

		private int maxAttempts = 50;

		private LongSupplier clock = System::currentTimeMillis;

		private @Nullable String username;

		private @Nullable String password;

		private @Nullable SSLContext sslContext;

		private Builder() {
		}

		/**
		 * Sets the etcd cluster's client URLs. Requests go to the one that last worked
		 * and move on when it cannot be reached.
		 * @param endpoints the client URLs, {@code http://} or {@code https://}
		 * @return this builder
		 */
		public Builder endpoints(List<String> endpoints) {
			if (endpoints.isEmpty()) {
				throw new IllegalArgumentException("at least one etcd endpoint is required");
			}
			this.endpoints.clear();
			this.endpoints.addAll(endpoints);
			return this;
		}

		/**
		 * Sets the prefix every key of this store lives under, which is what makes it one
		 * keyspace of its own. It must end with a separator so that one prefix cannot be
		 * the start of another.
		 * @param keyPrefix the prefix, ending in {@code /}
		 * @return this builder
		 */
		public Builder keyPrefix(String keyPrefix) {
			if (keyPrefix.isEmpty()) {
				throw new IllegalArgumentException("the etcd key prefix must not be empty");
			}
			this.keyPrefix = keyPrefix.endsWith("/") ? keyPrefix : keyPrefix + "/";
			return this;
		}

		/**
		 * Sets how long to wait for a connection to an endpoint.
		 * @param connectTimeout the connect timeout
		 * @return this builder
		 */
		public Builder connectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
			return this;
		}

		/**
		 * Sets how long to wait for etcd to answer a request. It bounds how long a Redis
		 * command can hang: a client waiting on a session is better told that something
		 * failed than left waiting.
		 * @param requestTimeout the request timeout
		 * @return this builder
		 */
		public Builder requestTimeout(Duration requestTimeout) {
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets how long to wait before opening the watch again after it fails.
		 * @param watchRetryDelay the delay between attempts
		 * @return this builder
		 */
		public Builder watchRetryDelay(Duration watchRetryDelay) {
			this.watchRetryDelay = watchRetryDelay;
			return this;
		}

		/**
		 * Sets how many times an operation re-reads a key that changed underneath it
		 * before giving up.
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
		 * Sets the credentials for a cluster with authentication enabled.
		 * @param username the etcd user, or {@code null} for a cluster without
		 * authentication
		 * @param password that user's password
		 * @return this builder
		 */
		public Builder credentials(@Nullable String username, @Nullable String password) {
			this.username = username;
			this.password = password;
			return this;
		}

		/**
		 * Sets the TLS context to reach an {@code https://} endpoint with, which is also
		 * where a client certificate comes from.
		 * @param sslContext the context, or {@code null} for the JDK's default
		 * @return this builder
		 */
		public Builder sslContext(@Nullable SSLContext sslContext) {
			this.sslContext = sslContext;
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

		/**
		 * Builds the store and starts watching the keyspace.
		 * @return a new store
		 */
		public EtcdKeyValueStore build() {
			EtcdKeyValueStore store = new EtcdKeyValueStore(this);
			// The watch is what delivers every key event, so a store without one would
			// leave
			// an application in indexed mode with no session events at all: it is not
			// optional.
			//
			// It has to resume from the revision this store was built at rather than from
			// whenever its request reaches etcd, or a key removed in between would go
			// unannounced — and the very first thing a caller does is exactly when that
			// happens, in a test or in a restarted adapter picking up sessions that are
			// already dying. Failing to read that revision is not worth refusing to start
			// over: every backend is created while the application starts, whether or not
			// it
			// is the one selected, so an etcd that is briefly away must not take the
			// server
			// with it. The watch then starts from wherever etcd is when it comes back.
			try {
				store.watchedRevision = store.client.revision(store.prefix);
			}
			catch (RuntimeException e) {
				logger.warn("Could not read the revision to watch {} from; a key removed "
						+ "before the watch opens will not be announced", store.keyPrefix(), e);
			}
			Thread watcher = Thread.ofVirtual().name("etcd-kvs-watch-" + store.keyPrefix()).unstarted(store::watch);
			store.watcher = watcher;
			watcher.start();
			return store;
		}

	}

}
