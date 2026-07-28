package am.ik.redis.adapter.dynamodb;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * A {@link KeyValueStore} that keeps the sessions in one DynamoDB table.
 *
 * <p>
 * This is the second shared backend: every adapter replica reads and writes the same
 * table, sessions outlive every adapter, and a key one replica removes is announced to
 * the clients of all of them. {@code .docs/design/architecture.md} §12 is the design;
 * what follows names only what a reader of this class needs.
 *
 * <h2>What a key holds</h2> A <em>meta item</em> per key carries the type, the deadline
 * and — for a string — the payload. A hash keeps one item per field in the meta item's
 * own partition, so reading a session is one {@code Query}; a set or sorted set keeps one
 * item per member, sharded over {@code shards} partitions, because DynamoDB caps one
 * partition at 1,000 writes per second and every session expiring in the same minute
 * joins one bucket. Writes are transactions guarded at the meta item.
 *
 * <h2>Expiry</h2> The deadline lives in the meta item and every read compares against it;
 * a read that finds an overdue key removes it, and the removal is what announces it. Keys
 * nobody touches are announced by a sweeper elected per database with a conditional-put
 * lease, fed by a sparse deadline index. DynamoDB's own TTL is written only as a storage
 * backstop, rounded up plus a margin — AWS collects best-effort within 48 hours, so it
 * must never be what fires {@code onExpired}.
 *
 * <h2>Key events</h2> Every removal that announces writes its log entry in the removal's
 * own transaction, with the reason as a field, into a time-bucketed log in the same
 * table. Every store polls its database's buckets and fires listeners from what it reads
 * — its own removals included — which is the etcd backend's watch with the watch replaced
 * by a poll. The cursor lags wall-clock by {@code cursorLag}, because an entry stamped
 * behind a cursor that has already passed would never be seen.
 *
 * <h2>Clocks</h2> Deadlines, log stamps and the cursor are absolute milliseconds on the
 * <em>adapter's</em> clock, so replicas need their clocks roughly in step. Skew within
 * {@code cursorLag} shows up as a key expiring or an event arriving that much early or
 * late, never as a lost session.
 */
public final class DynamoDbKeyValueStore implements KeyValueStore {

	private static final Logger logger = LoggerFactory.getLogger(DynamoDbKeyValueStore.class);

	/** The longest a retry waits, which bounds how long a contended command can take. */
	private static final long MAX_BACKOFF_MILLIS = 50;

	/** The most items one {@code TransactWriteItems} accepts; the 101st is refused. */
	private static final int TRANSACTION_LIMIT = 100;

	/** The most items one {@code BatchWriteItem} accepts; the 26th is refused. */
	private static final int BATCH_WRITE_LIMIT = 25;

	/** The most keys one {@code BatchGetItem} accepts. */
	private static final int BATCH_GET_LIMIT = 100;

	/**
	 * The most one item may weigh (400 KB, names and values together), checked before a
	 * write is sent. DynamoDB would refuse it anyway, but the emulator under the tests
	 * applies the rest of a transaction whose one item is oversized where AWS cancels it
	 * all — so the size failure has to be decided before anything is sent, or the store's
	 * behaviour would differ between the fake and the real thing.
	 */
	private static final long MAX_ITEM_BYTES = 400L * 1024;

	/** Room for the fixed attribute names and the type metadata of one item. */
	private static final long ITEM_OVERHEAD_BYTES = 256;

	/**
	 * What the storage-backstop TTL adds past the deadline. Wide enough that no
	 * emulator's prompt reaper can beat the sweeper to a live announcement, and the exact
	 * deadline is the {@code exp} attribute anyway; AWS's own collection runs up to 48
	 * hours late regardless.
	 */
	private static final long TTL_BACKSTOP_MARGIN_SECONDS = 300;

	/** How long a log entry's storage backstop outlives its bucket. */
	private static final long LOG_TTL_SECONDS = 3_600;

	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

	private static final String META_SK = "@";

	private static final String TYPE_STRING = "string";

	private static final String TYPE_HASH = "hash";

	private static final String TYPE_SET = "set";

	private static final String TYPE_ZSET = "zset";

	/** The deadline index. */
	private static final String DUE_INDEX = "due";

	private final DynamoDbClient client;

	private final String tableName;

	private final int databaseIndex;

	private final int shards;

	private final boolean createTable;

	private final Duration pollInterval;

	private final Duration cursorLag;

	private final Duration sweepInterval;

	private final Duration logRetention;

	private final Duration requestTimeout;

	private final int maxAttempts;

	private final LongSupplier clock;

	private final CopyOnWriteArrayList<KeyEventListener> listeners = new CopyOnWriteArrayList<>();

	/** What keeps this adapter's own callers from competing for the same key. */
	private final KeyLocks locks = new KeyLocks();

	/** Which holder this store is when it takes the sweeper lease. */
	private final String sweeperId = UUID.randomUUID().toString();

	private volatile boolean closed;

	private volatile boolean tableReady;

	private volatile @Nullable Thread poller;

	private volatile @Nullable Thread sweeper;

	private DynamoDbKeyValueStore(Builder builder) {
		this.client = Objects.requireNonNull(builder.client, "client");
		this.tableName = builder.tableName;
		this.databaseIndex = builder.databaseIndex;
		this.shards = builder.shards;
		this.createTable = builder.createTable;
		this.pollInterval = builder.pollInterval;
		this.cursorLag = builder.cursorLag;
		this.sweepInterval = builder.sweepInterval;
		this.logRetention = builder.logRetention;
		this.requestTimeout = builder.requestTimeout;
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
		List<Map<String, AttributeValue>> items = queryAll(metaPk(key), null, true);
		Meta meta = metaOf(items);
		if (meta == null) {
			return null;
		}
		if (meta.isExpired(currentTimeMillis())) {
			expireOverdue(key, meta);
			return null;
		}
		return switch (meta.type()) {
			case TYPE_STRING -> new StringValue(meta.requiredValue());
			case TYPE_HASH -> hashOf(items);
			case TYPE_SET -> setOf(key);
			case TYPE_ZSET -> zsetOf(key);
			default -> throw new DynamoDbBackendException("Key " + ByteArrayKey.of(key) + " holds an unknown type "
					+ meta.type() + "; the table was written by a different version of this backend");
		};
	}

	@Override
	public boolean exists(byte[] key) {
		return liveMeta(key) != null;
	}

	@Override
	public @Nullable Long getExpireAt(byte[] key) {
		Meta meta = liveMeta(key);
		return (meta == null) ? null : meta.expireAt();
	}

	/**
	 * Reads a key's meta item, honouring passive expiration: an overdue key is removed —
	 * which is what announces it, here and on every replica — before being reported
	 * absent.
	 * @param key the Redis key
	 * @return the meta item, or {@code null} if the key is absent or expired
	 */
	private @Nullable Meta liveMeta(byte[] key) {
		Meta meta = readMeta(key);
		if (meta == null) {
			return null;
		}
		if (meta.isExpired(currentTimeMillis())) {
			expireOverdue(key, meta);
			return null;
		}
		return meta;
	}

	private @Nullable Meta readMeta(byte[] key) {
		Map<String, AttributeValue> item = call("Reading " + ByteArrayKey.of(key),
				() -> this.client
					.getItem(r -> r.tableName(this.tableName)
						.key(itemKey(metaPk(key), META_SK))
						.consistentRead(true)
						.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout)))
					.item());
		return (item == null || item.isEmpty()) ? null : Meta.of(item);
	}

	private HashValue hashOf(List<Map<String, AttributeValue>> items) {
		Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
		for (Map<String, AttributeValue> item : items) {
			String sk = sk(item);
			if (sk.startsWith("f/")) {
				fields.put(ByteArrayKey.of(B64_DECODER.decode(sk.substring(2))),
						Objects.requireNonNull(item.get("v"), "v").b().asByteArray());
			}
		}
		return new HashValue(fields);
	}

	private SetValue setOf(byte[] key) {
		Set<ByteArrayKey> members = new LinkedHashSet<>();
		for (int shard = 0; shard < this.shards; shard++) {
			for (Map<String, AttributeValue> item : queryAll(memberPk(key, shard), "m/", true)) {
				members.add(ByteArrayKey.of(B64_DECODER.decode(sk(item).substring(2))));
			}
		}
		return new SetValue(members);
	}

	private ZSetValue zsetOf(byte[] key) {
		Map<ByteArrayKey, Double> scores = new LinkedHashMap<>();
		for (int shard = 0; shard < this.shards; shard++) {
			for (Map<String, AttributeValue> item : queryAll(memberPk(key, shard), "z/", true)) {
				scores.put(ByteArrayKey.of(B64_DECODER.decode(sk(item).substring(2))),
						Double.parseDouble(Objects.requireNonNull(item.get("score"), "score").n()));
			}
		}
		return new ZSetValue(scores);
	}

	// --- string ------------------------------------------------------------------------

	@Override
	public void set(byte[] key, byte[] value) {
		String what = "SET " + ByteArrayKey.of(key);
		requireFits(what, metaPk(key), META_SK, value.length);
		byte[] stored = value.clone();
		this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta meta = readMeta(key);
				if (meta != null && meta.isExpired(currentTimeMillis())) {
					// Redis expires the key first and creates it anew second, so the
					// death
					// of what was there is announced rather than swallowed by the write.
					expireOverdue(key, meta);
					meta = readMeta(key);
				}
				Map<String, AttributeValue> item = newMeta(key, TYPE_STRING);
				item.put("v", AttributeValue.fromB(SdkBytes.fromByteArray(stored)));
				// The whole meta item is replaced, so the deadline attributes go with the
				// old value — SET drops the TTL — and the incarnation counter moves on,
				// or
				// a removal guarded on the old one would take this value with it.
				boolean written = (meta == null)
						? putConditional(what, item, "attribute_not_exists(#pk)", Map.of("#pk", "pk"), Map.of())
						: replaceMeta(what, item, meta);
				if (written) {
					if (meta != null && !TYPE_STRING.equals(meta.type())) {
						// A string has no children, and the meta no longer names the type
						// that wrote them, so nothing can read them: they only have to
						// go.
						purgeChildren(key);
					}
					return true;
				}
				backOff(attempt);
			}
			throw contention("SET", key);
		});
	}

	private boolean replaceMeta(String what, Map<String, AttributeValue> item, Meta meta) {
		item.put("ver", number(meta.version() + 1));
		return putConditional(what, item, "#ver = :ver", Map.of("#ver", "ver"), Map.of(":ver", number(meta.version())));
	}

	@Override
	public int append(byte[] key, byte[] value) {
		return this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta meta = liveMeta(key);
				if (meta == null) {
					requireFits("APPEND " + ByteArrayKey.of(key), metaPk(key), META_SK, value.length);
					Map<String, AttributeValue> item = newMeta(key, TYPE_STRING);
					item.put("v", AttributeValue.fromB(SdkBytes.fromByteArray(value)));
					if (putConditional("APPEND " + ByteArrayKey.of(key), item, "attribute_not_exists(#pk)",
							Map.of("#pk", "pk"), Map.of())) {
						return value.length;
					}
					backOff(attempt);
					continue;
				}
				if (!TYPE_STRING.equals(meta.type())) {
					throw new TypeMismatchException("APPEND against a key that does not hold a string");
				}
				byte[] current = meta.requiredValue();
				requireFits("APPEND " + ByteArrayKey.of(key), metaPk(key), META_SK, current.length + value.length);
				byte[] combined = new byte[current.length + value.length];
				System.arraycopy(current, 0, combined, 0, current.length);
				System.arraycopy(value, 0, combined, current.length, value.length);
				if (updateConditional("APPEND " + ByteArrayKey.of(key), metaKey(key), "SET #v = :v, #ver = :nver",
						"attribute_exists(#pk) AND #ver = :ver", Map.of("#pk", "pk", "#v", "v", "#ver", "ver"),
						Map.of(":v", AttributeValue.fromB(SdkBytes.fromByteArray(combined)), ":ver",
								number(meta.version()), ":nver", number(meta.version() + 1)))) {
					return combined.length;
				}
				backOff(attempt);
			}
			throw contention("APPEND", key);
		});
	}

	// --- hash --------------------------------------------------------------------------

	@Override
	public int hset(byte[] key, Map<byte[], byte[]> fields) {
		return this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				List<Map<String, AttributeValue>> items = queryAll(metaPk(key), null, true);
				Meta meta = metaOf(items);
				if (meta != null && meta.isExpired(currentTimeMillis())) {
					// Redis expires the key first and creates it anew second, so the
					// expiry is announced before this operation's value exists.
					expireOverdue(key, meta);
					continue;
				}
				// Last-wins over the wire order, and distinct names are what count.
				Map<String, byte[]> named = new LinkedHashMap<>();
				for (Map.Entry<byte[], byte[]> field : fields.entrySet()) {
					named.put(B64.encodeToString(field.getKey()), field.getValue());
				}
				Set<String> existing = new HashSet<>();
				for (Map<String, AttributeValue> item : items) {
					String sk = sk(item);
					if (sk.startsWith("f/")) {
						existing.add(sk.substring(2));
					}
				}
				List<ChildWrite> children = new ArrayList<>();
				for (Map.Entry<String, byte[]> field : named.entrySet()) {
					requireFits("HSET " + ByteArrayKey.of(key), metaPk(key), "f/" + field.getKey(),
							field.getValue().length);
					Map<String, AttributeValue> item = itemKey(metaPk(key), "f/" + field.getKey());
					item.put("v", AttributeValue.fromB(SdkBytes.fromByteArray(field.getValue())));
					children.add(ChildWrite.put(item));
				}
				if (meta == null) {
					// Stray fields under this partition are leftovers of a key that
					// crashed mid-removal; a new key starts empty, so they go now.
					for (String stray : existing) {
						if (!named.containsKey(stray)) {
							children.add(ChildWrite.delete(itemKey(metaPk(key), "f/" + stray)));
						}
					}
					if (applyGuarded("HSET " + ByteArrayKey.of(key), createMetaGuard(key, TYPE_HASH), children)) {
						return named.size();
					}
					backOff(attempt);
					continue;
				}
				if (!TYPE_HASH.equals(meta.type())) {
					throw new TypeMismatchException("HSET against a key that does not hold a hash");
				}
				int added = 0;
				for (String name : named.keySet()) {
					if (!existing.contains(name)) {
						added++;
					}
				}
				if (applyGuarded("HSET " + ByteArrayKey.of(key), liveMetaGuard(key, TYPE_HASH), children)) {
					return added;
				}
				backOff(attempt);
			}
			throw contention("HSET", key);
		});
	}

	// --- set ---------------------------------------------------------------------------

	@Override
	public int sadd(byte[] key, List<byte[]> members) {
		return addMembers(key, distinct(members), TYPE_SET, "SADD", null);
	}

	@Override
	public int srem(byte[] key, List<byte[]> members) {
		return removeMembers(key, distinct(members), TYPE_SET, "SREM");
	}

	// --- sorted set --------------------------------------------------------------------

	@Override
	public int zadd(byte[] key, Map<byte[], Double> scoredMembers) {
		Map<ByteArrayKey, Double> scored = new LinkedHashMap<>();
		for (Map.Entry<byte[], Double> member : scoredMembers.entrySet()) {
			scored.put(ByteArrayKey.of(member.getKey()), member.getValue());
		}
		return addMembers(key, new LinkedHashSet<>(scored.keySet()), TYPE_ZSET, "ZADD", scored);
	}

	@Override
	public int zrem(byte[] key, List<byte[]> members) {
		return removeMembers(key, distinct(members), TYPE_ZSET, "ZREM");
	}

	/**
	 * Adds members to a set or sorted set, creating the key if it is absent.
	 *
	 * <p>
	 * An existing key gets a {@code ConditionCheck} that the meta item is still there,
	 * still this type and not past its deadline — deliberately not a version compare, so
	 * two replicas adding different members to the same expirations bucket do not
	 * conflict at all. The count of newly added members is read before the write, which
	 * makes it exact within this adapter (callers of one key are serialized) and
	 * approximate across replicas.
	 * @param key the Redis key
	 * @param members the distinct members, in arrival order
	 * @param type {@link #TYPE_SET} or {@link #TYPE_ZSET}
	 * @param operation the Redis command, for messages
	 * @param scores the member scores for a sorted set, or {@code null} for a set
	 * @return how many members were newly added
	 */
	private int addMembers(byte[] key, Set<ByteArrayKey> members, String type, String operation,
			@Nullable Map<ByteArrayKey, Double> scores) {
		return this.locks.exclusively(key, () -> {
			String skPrefix = TYPE_SET.equals(type) ? "m/" : "z/";
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta meta = liveMeta(key);
				if (meta == null) {
					List<ChildWrite> children = memberWrites(key, members, skPrefix, scores);
					// Stray members are leftovers of a key that crashed mid-removal or
					// lost the emptied-set race; a new key starts empty, so they go now.
					Set<ItemKey> written = new HashSet<>();
					for (ChildWrite child : children) {
						written.add(ItemKey.of(child.item()));
					}
					for (int shard = 0; shard < this.shards; shard++) {
						for (Map<String, AttributeValue> stray : queryAll(memberPk(key, shard), null, true)) {
							ItemKey strayKey = ItemKey.of(stray);
							if (!written.contains(strayKey)) {
								children.add(ChildWrite.delete(itemKey(strayKey.pk(), strayKey.sk())));
							}
						}
					}
					if (applyGuarded(operation + " " + ByteArrayKey.of(key), createMetaGuard(key, type), children)) {
						return members.size();
					}
					backOff(attempt);
					continue;
				}
				if (!type.equals(meta.type())) {
					throw new TypeMismatchException(operation + " against a key that does not hold a "
							+ (TYPE_SET.equals(type) ? "set" : "sorted set"));
				}
				Set<ByteArrayKey> present = presentMembers(key, members, skPrefix);
				int added = 0;
				for (ByteArrayKey member : members) {
					if (!present.contains(member)) {
						added++;
					}
				}
				// A sorted set writes every member, because an existing one may be
				// moving to a new score; a set only writes what is new.
				Set<ByteArrayKey> toWrite = (scores != null) ? members : new LinkedHashSet<>(members);
				if (scores == null) {
					toWrite.removeAll(present);
				}
				if (toWrite.isEmpty()) {
					return 0;
				}
				List<ChildWrite> children = memberWrites(key, toWrite, skPrefix, scores);
				if (applyGuarded(operation + " " + ByteArrayKey.of(key), liveMetaGuard(key, type), children)) {
					return added;
				}
				backOff(attempt);
			}
			throw contention(operation, key);
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
		return this.locks.exclusively(key, () -> {
			String skPrefix = TYPE_SET.equals(type) ? "m/" : "z/";
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta meta = liveMeta(key);
				if (meta == null) {
					return 0;
				}
				if (!type.equals(meta.type())) {
					throw new TypeMismatchException(operation + " against a key that does not hold a "
							+ (TYPE_SET.equals(type) ? "set" : "sorted set"));
				}
				Set<ByteArrayKey> present = presentMembers(key, members, skPrefix);
				if (present.isEmpty()) {
					return 0;
				}
				List<ChildWrite> children = new ArrayList<>();
				for (ByteArrayKey member : present) {
					children.add(ChildWrite.delete(memberItemKey(key, member, skPrefix)));
				}
				if (!applyGuarded(operation + " " + ByteArrayKey.of(key), liveMetaGuard(key, type), children)) {
					backOff(attempt);
					continue;
				}
				if (!hasMembers(key)) {
					// Redis removes an emptied set without announcing it. The delete is
					// guarded on the incarnation, so a key that was replaced underneath
					// is left alone; a member another replica is adding right now can
					// still be stranded, which .docs/design/architecture.md §12.1 accepts
					// and bounds.
					removeSilently(key, meta);
				}
				return present.size();
			}
			throw contention(operation, key);
		});
	}

	private List<ChildWrite> memberWrites(byte[] key, Set<ByteArrayKey> members, String skPrefix,
			@Nullable Map<ByteArrayKey, Double> scores) {
		List<ChildWrite> children = new ArrayList<>();
		for (ByteArrayKey member : members) {
			requireFits((scores != null ? "ZADD " : "SADD ") + ByteArrayKey.of(key), metaPk(key),
					skPrefix + B64.encodeToString(member.asBytes()), 0);
			Map<String, AttributeValue> item = memberItemKey(key, member, skPrefix);
			if (scores != null) {
				item.put("score", number(Objects.requireNonNull(scores.get(member), "score")));
			}
			children.add(ChildWrite.put(item));
		}
		return children;
	}

	/**
	 * Returns which of the given members are currently in the collection, read as items
	 * rather than as a whole value: flat with the collection's size, which is the reason
	 * for the per-member layout.
	 * @param key the Redis key
	 * @param members the members being asked about
	 * @param skPrefix the member kind
	 * @return the members that exist
	 */
	private Set<ByteArrayKey> presentMembers(byte[] key, Set<ByteArrayKey> members, String skPrefix) {
		Set<ByteArrayKey> present = new HashSet<>();
		List<ByteArrayKey> pending = new ArrayList<>(members);
		for (int from = 0; from < pending.size(); from += BATCH_GET_LIMIT) {
			List<ByteArrayKey> chunk = pending.subList(from, Math.min(from + BATCH_GET_LIMIT, pending.size()));
			List<Map<String, AttributeValue>> keys = new ArrayList<>();
			for (ByteArrayKey member : chunk) {
				keys.add(memberItemKey(key, member, skPrefix));
			}
			Map<String, KeysAndAttributes> request = Map.of(this.tableName,
					KeysAndAttributes.builder().keys(keys).consistentRead(true).build());
			Map<String, KeysAndAttributes> remaining = request;
			for (int attempt = 1; attempt <= this.maxAttempts && !remaining.isEmpty(); attempt++) {
				Map<String, KeysAndAttributes> ask = remaining;
				BatchGetItemResponse response = call("Reading members of " + ByteArrayKey.of(key),
						() -> this.client.batchGetItem(r -> r.requestItems(ask)
							.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout))));
				for (Map<String, AttributeValue> item : response.responses().getOrDefault(this.tableName, List.of())) {
					present.add(ByteArrayKey.of(B64_DECODER.decode(sk(item).substring(2))));
				}
				remaining = response.unprocessedKeys();
				if (!remaining.isEmpty()) {
					backOff(attempt);
				}
			}
			if (!remaining.isEmpty()) {
				throw new DynamoDbBackendException("Reading members of " + ByteArrayKey.of(key)
						+ " kept coming back unprocessed after " + this.maxAttempts + " attempts");
			}
		}
		return present;
	}

	private boolean hasMembers(byte[] key) {
		for (int shard = 0; shard < this.shards; shard++) {
			int pkShard = shard;
			QueryResponse response = call("Counting members of " + ByteArrayKey.of(key),
					() -> this.client.query(r -> r.tableName(this.tableName)
						.keyConditionExpression("#pk = :pk")
						.expressionAttributeNames(Map.of("#pk", "pk"))
						.expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(memberPk(key, pkShard))))
						.select(Select.COUNT)
						.limit(1)
						.consistentRead(true)
						.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout))));
			if (response.count() > 0) {
				return true;
			}
		}
		return false;
	}

	// --- generic key -------------------------------------------------------------------

	@Override
	public boolean delete(byte[] key) {
		return this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta meta = readMeta(key);
				if (meta == null) {
					return false;
				}
				if (meta.isExpired(currentTimeMillis())) {
					// Redis announces the lazy expiry rather than the delete, and
					// answers as if the key had already been gone.
					expireOverdue(key, meta);
					return false;
				}
				if (removeAnnouncing(key, meta, Removal.DELETED)) {
					return true;
				}
				backOff(attempt);
			}
			throw contention("DEL", key);
		});
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The source is held for the duration; the destination is not, since Redis overwrites
	 * it whatever it held. The move itself is one transaction — the destination's meta
	 * item appears and the source's goes together — and no log entry is written anywhere,
	 * because a rename must not look like a delete. The children travel outside that
	 * transaction, which the SPI already allows.
	 */
	@Override
	public boolean rename(byte[] source, byte[] destination) {
		if (java.util.Arrays.equals(source, destination)) {
			// Redis renames a key onto itself as a no-op; moving would purge what is
			// being moved.
			return liveMeta(source) != null;
		}
		return this.locks.exclusively(source, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				Meta sourceMeta = readMeta(source);
				if (sourceMeta == null) {
					return false;
				}
				long now = currentTimeMillis();
				if (sourceMeta.isExpired(now)) {
					// Redis expires the source lazily and the rename then fails, so the
					// expiry is announced rather than swallowed by the move.
					expireOverdue(source, sourceMeta);
					return false;
				}
				Meta destinationMeta = readMeta(destination);
				if (destinationMeta != null && destinationMeta.isExpired(now)) {
					// An overdue destination dies announced before it is overwritten; a
					// session overwritten without a word is one the application never
					// hears has ended.
					expireOverdue(destination, destinationMeta);
				}
				// Whatever the destination held — a live value being overwritten in
				// silence, or strays of an older key — its children go before the new
				// ones arrive, or a stale field would read as part of the moved value.
				purgeChildren(destination);
				writeChildren(destination, source, sourceMeta.type());
				List<TransactWriteItem> move = new ArrayList<>();
				move.add(TransactWriteItem.builder()
					.put(p -> p.tableName(this.tableName).item(renamedMeta(destination, sourceMeta)))
					.build());
				move.add(deleteMetaGuarded(source, sourceMeta));
				if (transact("RENAME " + ByteArrayKey.of(source), move)) {
					deleteChildren(source, sourceMeta.type());
					return true;
				}
				backOff(attempt);
			}
			throw contention("RENAME", source);
		});
	}

	private Map<String, AttributeValue> renamedMeta(byte[] destination, Meta sourceMeta) {
		Map<String, AttributeValue> item = newMeta(destination, sourceMeta.type());
		byte[] value = sourceMeta.value();
		if (value != null) {
			item.put("v", AttributeValue.fromB(SdkBytes.fromByteArray(value)));
		}
		Long expireAt = sourceMeta.expireAt();
		if (expireAt != null) {
			item.put("exp", number(expireAt));
			item.put("ttl", number(ttlBackstopSeconds(expireAt)));
			item.put("duePk", AttributeValue.fromS(duePk(destination)));
			item.put("dueAt", number(expireAt));
		}
		return item;
	}

	private void writeChildren(byte[] destination, byte[] source, String type) {
		switch (type) {
			case TYPE_HASH -> {
				List<ChildWrite> writes = new ArrayList<>();
				for (Map<String, AttributeValue> item : queryAll(metaPk(source), "f/", true)) {
					Map<String, AttributeValue> copy = itemKey(metaPk(destination), sk(item));
					copy.put("v", item.get("v"));
					writes.add(ChildWrite.put(copy));
				}
				batchWrite("RENAME " + ByteArrayKey.of(source), writes);
			}
			case TYPE_SET, TYPE_ZSET -> {
				String skPrefix = TYPE_SET.equals(type) ? "m/" : "z/";
				List<ChildWrite> writes = new ArrayList<>();
				for (int shard = 0; shard < this.shards; shard++) {
					for (Map<String, AttributeValue> item : queryAll(memberPk(source, shard), skPrefix, true)) {
						ByteArrayKey member = ByteArrayKey.of(B64_DECODER.decode(sk(item).substring(2)));
						Map<String, AttributeValue> copy = memberItemKey(destination, member, skPrefix);
						AttributeValue score = item.get("score");
						if (score != null) {
							copy.put("score", score);
						}
						writes.add(ChildWrite.put(copy));
					}
				}
				batchWrite("RENAME " + ByteArrayKey.of(source), writes);
			}
			default -> {
				// a string travels in the meta item itself
			}
		}
	}

	// --- ttl ---------------------------------------------------------------------------

	@Override
	public boolean expireAt(byte[] key, long epochMilli) {
		return this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				// The new deadline does not depend on the old, so this is one write and
				// no read — the "deadline folded into the write" price, on the operation
				// Spring Session issues three times per save.
				if (updateConditional("PEXPIREAT " + ByteArrayKey.of(key), metaKey(key),
						"SET #exp = :exp, #ttl = :ttl, #duePk = :duePk, #dueAt = :exp",
						"attribute_exists(#pk) AND (attribute_not_exists(#exp) OR #exp > :now)",
						Map.of("#pk", "pk", "#exp", "exp", "#ttl", "ttl", "#duePk", "duePk", "#dueAt", "dueAt"),
						Map.of(":exp", number(epochMilli), ":ttl", number(ttlBackstopSeconds(epochMilli)), ":duePk",
								AttributeValue.fromS(duePk(key)), ":now", number(currentTimeMillis())))) {
					return true;
				}
				Meta meta = readMeta(key);
				if (meta == null) {
					return false;
				}
				if (meta.isExpired(currentTimeMillis())) {
					expireOverdue(key, meta);
					return false;
				}
				backOff(attempt);
			}
			throw contention("PEXPIREAT", key);
		});
	}

	@Override
	public boolean persist(byte[] key) {
		return this.locks.exclusively(key, () -> {
			for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
				if (updateConditional("PERSIST " + ByteArrayKey.of(key), metaKey(key),
						"REMOVE #exp, #ttl, #duePk, #dueAt",
						"attribute_exists(#pk) AND attribute_exists(#exp) AND #exp > :now",
						Map.of("#pk", "pk", "#exp", "exp", "#ttl", "ttl", "#duePk", "duePk", "#dueAt", "dueAt"),
						Map.of(":now", number(currentTimeMillis())))) {
					return true;
				}
				Meta meta = readMeta(key);
				if (meta == null) {
					return false;
				}
				if (meta.isExpired(currentTimeMillis())) {
					expireOverdue(key, meta);
					return false;
				}
				if (meta.expireAt() == null) {
					return false;
				}
				backOff(attempt);
			}
			throw contention("PERSIST", key);
		});
	}

	private long ttlBackstopSeconds(long expireAtMillis) {
		return Math.ceilDiv(expireAtMillis, 1000) + TTL_BACKSTOP_MARGIN_SECONDS;
	}

	// --- removal -----------------------------------------------------------------------

	/**
	 * Removes an overdue key and announces the expiry. The removal is guarded on the meta
	 * item as it was read, so a deadline another replica pushed out in the meantime — or
	 * a removal another replica already made — leaves this one a silent no-op.
	 * @param key the Redis key
	 * @param meta the meta item as it was read, already overdue
	 */
	private void expireOverdue(byte[] key, Meta meta) {
		removeAnnouncing(key, meta, Removal.EXPIRED);
	}

	/**
	 * Removes a key and announces it: one transaction deletes the meta item and writes
	 * the key-event log entry with the reason as a field, so the removal and its
	 * announcement cannot part company. The children go afterwards — they are already
	 * unreadable the moment the meta is gone.
	 * @param key the Redis key
	 * @param meta the meta item as it was read, which guards the delete
	 * @param removal why, which is what every replica's poller will report
	 * @return {@code true} if this call removed the key; {@code false} if it had changed
	 * or gone underneath
	 */
	private boolean removeAnnouncing(byte[] key, Meta meta, Removal removal) {
		List<TransactWriteItem> ops = new ArrayList<>();
		ops.add(deleteMetaGuarded(key, meta));
		ops.add(TransactWriteItem.builder().put(p -> p.tableName(this.tableName).item(logEntry(key, removal))).build());
		if (!transact((removal == Removal.DELETED ? "DEL " : "Expiring ") + ByteArrayKey.of(key), ops)) {
			return false;
		}
		deleteChildren(key, meta.type());
		return true;
	}

	/**
	 * Removes an emptied collection's key without announcing it, as Redis does.
	 * @param key the Redis key
	 * @param meta the meta item as it was read
	 */
	private void removeSilently(byte[] key, Meta meta) {
		List<TransactWriteItem> ops = new ArrayList<>();
		ops.add(deleteMetaGuarded(key, meta));
		if (transact("Removing the emptied " + ByteArrayKey.of(key), ops)) {
			deleteChildren(key, meta.type());
		}
	}

	private TransactWriteItem deleteMetaGuarded(byte[] key, Meta meta) {
		Long expireAt = meta.expireAt();
		String condition = (expireAt != null) ? "#ver = :ver AND #exp = :exp"
				: "#ver = :ver AND attribute_not_exists(#exp)";
		Map<String, AttributeValue> values = new HashMap<>();
		values.put(":ver", number(meta.version()));
		if (expireAt != null) {
			values.put(":exp", number(expireAt));
		}
		return TransactWriteItem.builder()
			.delete(d -> d.tableName(this.tableName)
				.key(metaKey(key))
				.conditionExpression(condition)
				.expressionAttributeNames(Map.of("#ver", "ver", "#exp", "exp"))
				.expressionAttributeValues(values))
			.build();
	}

	private void deleteChildren(byte[] key, String type) {
		switch (type) {
			case TYPE_HASH -> deleteAllUnder(metaPk(key), "f/");
			case TYPE_SET, TYPE_ZSET -> {
				for (int shard = 0; shard < this.shards; shard++) {
					deleteAllUnder(memberPk(key, shard), null);
				}
			}
			default -> {
				// a string has no children
			}
		}
	}

	/**
	 * Deletes every child item that could exist under a key, whatever type wrote it. A
	 * rename's destination is cleared with this, because what it held before is nobody's
	 * to read afterwards.
	 * @param key the Redis key
	 */
	private void purgeChildren(byte[] key) {
		deleteAllUnder(metaPk(key), "f/");
		for (int shard = 0; shard < this.shards; shard++) {
			deleteAllUnder(memberPk(key, shard), null);
		}
	}

	private void deleteAllUnder(String pk, @Nullable String skPrefix) {
		List<ChildWrite> deletes = new ArrayList<>();
		for (Map<String, AttributeValue> item : queryAll(pk, skPrefix, true)) {
			deletes.add(ChildWrite.delete(itemKey(pk, sk(item))));
		}
		batchWrite("Clearing " + pk, deletes);
	}

	// --- events ------------------------------------------------------------------------

	@Override
	public void addKeyEventListener(KeyEventListener listener) {
		this.listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	private Map<String, AttributeValue> logEntry(byte[] key, Removal removal) {
		long now = currentTimeMillis();
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("pk", AttributeValue.fromS(logPk(Math.floorDiv(now, 1000))));
		item.put("sk", AttributeValue.fromS("%013d/%s".formatted(now, UUID.randomUUID())));
		item.put("key", AttributeValue.fromB(SdkBytes.fromByteArray(key)));
		item.put("reason", AttributeValue.fromS(removal == Removal.EXPIRED ? "expired" : "del"));
		item.put("ttl", number(Math.floorDiv(now, 1000) + LOG_TTL_SECONDS));
		return item;
	}

	/**
	 * Follows the database's event log, turning every entry into the key event it
	 * records.
	 *
	 * <p>
	 * This is the whole of this backend's event delivery — this store's own removals
	 * included, so every replica hears every event in the same order. The cursor lags
	 * wall-clock by {@code cursorLag} and never goes back, so an entry stamped behind it
	 * but written after it passed is lost; the lag has to outlast the fleet's clock skew
	 * plus a write's latency, which is what makes it a correctness setting rather than a
	 * latency knob.
	 */
	private void poll() {
		long cursorBucket = Math.floorDiv(currentTimeMillis() - this.cursorLag.toMillis(), 1000);
		String afterSk = "%013d".formatted(currentTimeMillis() - this.cursorLag.toMillis());
		while (!this.closed) {
			try {
				Thread.sleep(this.pollInterval);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				long cutoff = currentTimeMillis() - this.cursorLag.toMillis();
				long targetBucket = Math.floorDiv(cutoff, 1000);
				while (cursorBucket <= targetBucket && !this.closed) {
					boolean drained = true;
					for (Map<String, AttributeValue> entry : queryAll(logPk(cursorBucket), null, true, afterSk)) {
						String sk = sk(entry);
						long stamped = Long.parseLong(sk.substring(0, sk.indexOf('/')));
						if (stamped > cutoff) {
							// still inside the lag window; whoever is writing this
							// second is not necessarily done with it
							drained = false;
							break;
						}
						dispatch(entry);
						afterSk = sk;
					}
					if (!drained || cursorBucket == targetBucket) {
						break;
					}
					cursorBucket++;
					afterSk = "";
				}
			}
			catch (RuntimeException e) {
				if (!this.closed) {
					logger.warn("Polling the key-event log of database {} failed; retrying in {}", this.databaseIndex,
							this.pollInterval, e);
				}
			}
		}
	}

	private void dispatch(Map<String, AttributeValue> entry) {
		AttributeValue keyValue = entry.get("key");
		AttributeValue reason = entry.get("reason");
		if (keyValue == null || reason == null) {
			logger.debug("A key-event log entry said nothing usable: {}", entry);
			return;
		}
		byte[] key = keyValue.b().asByteArray();
		if ("expired".equals(reason.s())) {
			fireExpired(key);
		}
		else {
			fireDeleted(key);
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

	// --- sweeper -----------------------------------------------------------------------

	/**
	 * Announces the keys nobody touches. One replica sweeps per database, elected by a
	 * conditional-put lease; the others keep polling the log, so they still hear what the
	 * holder announces. Losing the lease costs nothing, a dead holder's lease lapses, and
	 * a live replica takes it.
	 */
	private void sweep() {
		long trimFromBucket = Math.floorDiv(currentTimeMillis() - this.logRetention.toMillis(), 1000);
		while (!this.closed) {
			try {
				Thread.sleep(this.sweepInterval);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			try {
				if (!this.tableReady) {
					ensureTable();
				}
				if (!acquireLease()) {
					continue;
				}
				sweepOverdue();
				trimFromBucket = trimLog(trimFromBucket);
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

	private boolean acquireLease() {
		long now = currentTimeMillis();
		long leaseMillis = Math.max(this.sweepInterval.toMillis() * 5, 5_000);
		Map<String, AttributeValue> lease = itemKey(leasePk(), META_SK);
		lease.put("holder", AttributeValue.fromS(this.sweeperId));
		lease.put("leaseUntil", number(now + leaseMillis));
		return putConditional("Taking the sweeper lease of database " + this.databaseIndex, lease,
				"attribute_not_exists(#pk) OR #holder = :me OR #leaseUntil < :now",
				Map.of("#pk", "pk", "#holder", "holder", "#leaseUntil", "leaseUntil"),
				Map.of(":me", AttributeValue.fromS(this.sweeperId), ":now", number(now)));
	}

	private void releaseLease() {
		try {
			this.client.deleteItem(r -> r.tableName(this.tableName)
				.key(itemKey(leasePk(), META_SK))
				.conditionExpression("#holder = :me")
				.expressionAttributeNames(Map.of("#holder", "holder"))
				.expressionAttributeValues(Map.of(":me", AttributeValue.fromS(this.sweeperId)))
				.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout)));
		}
		catch (RuntimeException e) {
			// Somebody else holds it, or nobody does; either way it is not this store's
			// to release, and a lease left behind lapses on its own.
			logger.debug("Could not release the sweeper lease of database {}", this.databaseIndex, e);
		}
	}

	/**
	 * Finds overdue keys through the deadline index and removes each through the same
	 * guarded, announcing removal every read uses. The index is eventually consistent —
	 * it only nominates; the meta item, read strongly consistent, decides.
	 */
	private void sweepOverdue() {
		long now = currentTimeMillis();
		for (int shard = 0; shard < this.shards; shard++) {
			int dueShard = shard;
			QueryResponse response = call("Reading the deadline index of database " + this.databaseIndex,
					() -> this.client.query(r -> r.tableName(this.tableName)
						.indexName(DUE_INDEX)
						.keyConditionExpression("#duePk = :duePk AND #dueAt <= :now")
						.expressionAttributeNames(Map.of("#duePk", "duePk", "#dueAt", "dueAt"))
						.expressionAttributeValues(Map.of(":duePk",
								AttributeValue.fromS("d/" + this.databaseIndex + "/" + dueShard), ":now", number(now)))
						.limit(100)
						.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout))));
			for (Map<String, AttributeValue> nominee : response.items()) {
				byte[] key = keyOfMetaPk(Objects.requireNonNull(nominee.get("pk"), "pk").s());
				if (key == null) {
					continue;
				}
				Meta meta = readMeta(key);
				if (meta != null && meta.isExpired(currentTimeMillis())) {
					expireOverdue(key, meta);
				}
			}
		}
	}

	/**
	 * Deletes log buckets everything has long since read. The storage backstop on each
	 * entry would reclaim them eventually; this keeps the log the size of its retention
	 * rather than of AWS's patience.
	 * @param fromBucket the first bucket not yet trimmed
	 * @return the first bucket still untrimmed afterwards
	 */
	private long trimLog(long fromBucket) {
		long targetBucket = Math.floorDiv(currentTimeMillis() - this.logRetention.toMillis(), 1000);
		long bucket = fromBucket;
		for (int budget = 60; bucket < targetBucket && budget > 0; bucket++, budget--) {
			deleteAllUnder(logPk(bucket), null);
		}
		return bucket;
	}

	// --- schema ------------------------------------------------------------------------

	/**
	 * Makes sure the table is there, creating it — on-demand billing, the deadline index,
	 * the TTL backstop — when it is absent and this store was told to. A backend that
	 * requires DDL by hand is a bad first experience, and there is no replication
	 * strategy here to get wrong.
	 */
	private void ensureTable() {
		try {
			waitUntilActive();
			this.tableReady = true;
			return;
		}
		catch (ResourceNotFoundException e) {
			if (!this.createTable) {
				throw new DynamoDbBackendException(
						"Table " + this.tableName + " does not exist and redis-adapter.dynamodb.create-table is off",
						e);
			}
		}
		try {
			this.client.createTable(r -> r.tableName(this.tableName)
				.attributeDefinitions(a -> a.attributeName("pk").attributeType(ScalarAttributeType.S),
						a -> a.attributeName("sk").attributeType(ScalarAttributeType.S),
						a -> a.attributeName("duePk").attributeType(ScalarAttributeType.S),
						a -> a.attributeName("dueAt").attributeType(ScalarAttributeType.N))
				.keySchema(k -> k.attributeName("pk").keyType(KeyType.HASH),
						k -> k.attributeName("sk").keyType(KeyType.RANGE))
				.globalSecondaryIndexes(g -> g.indexName(DUE_INDEX)
					.keySchema(k -> k.attributeName("duePk").keyType(KeyType.HASH),
							k -> k.attributeName("dueAt").keyType(KeyType.RANGE))
					.projection(p -> p.projectionType(ProjectionType.KEYS_ONLY)))
				.billingMode(BillingMode.PAY_PER_REQUEST)
				.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout)));
		}
		catch (ResourceInUseException e) {
			// another store, or another replica, got there first — which is fine
		}
		waitUntilActive();
		enableTtl();
		this.tableReady = true;
	}

	private void waitUntilActive() {
		long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		while (true) {
			TableStatus status = this.client.describeTable(
					r -> r.tableName(this.tableName).overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout)))
				.table()
				.tableStatus();
			if (status == TableStatus.ACTIVE) {
				return;
			}
			if (System.nanoTime() > deadline) {
				throw new DynamoDbBackendException("Table " + this.tableName + " stayed " + status);
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DynamoDbBackendException("Interrupted while waiting for table " + this.tableName, e);
			}
		}
	}

	private void enableTtl() {
		try {
			this.client.updateTimeToLive(r -> r.tableName(this.tableName)
				.timeToLiveSpecification(s -> s.enabled(true).attributeName("ttl"))
				.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout)));
		}
		catch (DynamoDbException e) {
			String message = String.valueOf(e.getMessage());
			if (!message.contains("already enabled")) {
				throw e;
			}
		}
	}

	/**
	 * Checks that DynamoDB answers, by the same read every operation makes, credentials
	 * and all. A table that is away fails in bounded time, so this is an ordinary call
	 * rather than a race against a timeout.
	 * @throws DynamoDbBackendException if the table could not be reached
	 */
	public void checkHealth() {
		readMeta(new byte[0]);
	}

	/**
	 * Returns the table this store keeps its keys in.
	 * @return the table name
	 */
	public String tableName() {
		return this.tableName;
	}

	/**
	 * Returns the database this store serves, which prefixes every partition key and is
	 * what separates one database from another in the shared table.
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
		Thread poller = this.poller;
		if (poller != null) {
			poller.interrupt();
		}
		Thread sweeper = this.sweeper;
		if (sweeper != null) {
			sweeper.interrupt();
		}
		// The client is not closed: it was handed in, and it is the caller's.
	}

	// --- the meta item -----------------------------------------------------------------

	/**
	 * What the meta item of a key held when it was read.
	 *
	 * @param type one of the four Redis types
	 * @param value the string payload, or {@code null} for a collection
	 * @param expireAt the absolute deadline in epoch milliseconds, or {@code null} for a
	 * key that does not expire
	 * @param version the incarnation counter that guards a removal and an append against
	 * a key replaced underneath them
	 */
	private record Meta(String type, byte @Nullable [] value, @Nullable Long expireAt, long version) {

		static Meta of(Map<String, AttributeValue> item) {
			AttributeValue value = item.get("v");
			AttributeValue expireAt = item.get("exp");
			return new Meta(Objects.requireNonNull(item.get("t"), "t").s(),
					(value == null) ? null : value.b().asByteArray(),
					(expireAt == null) ? null : Long.parseLong(expireAt.n()),
					Long.parseLong(Objects.requireNonNull(item.get("ver"), "ver").n()));
		}

		boolean isExpired(long now) {
			return this.expireAt != null && this.expireAt <= now;
		}

		byte[] requiredValue() {
			return Objects.requireNonNull(this.value, "a string meta item holds its value");
		}

	}

	private Map<String, AttributeValue> newMeta(byte[] key, String type) {
		Map<String, AttributeValue> item = itemKey(metaPk(key), META_SK);
		item.put("t", AttributeValue.fromS(type));
		item.put("ver", number(1));
		return item;
	}

	private @Nullable Meta metaOf(List<Map<String, AttributeValue>> items) {
		for (Map<String, AttributeValue> item : items) {
			if (META_SK.equals(sk(item))) {
				return Meta.of(item);
			}
		}
		return null;
	}

	/** Why a key was removed, which is what its log entry carries. */
	private enum Removal {

		EXPIRED, DELETED

	}

	// --- guarded writes ----------------------------------------------------------------

	/**
	 * The meta-item operation of a mutation that creates the key: the put lands only if
	 * nothing created it in the meantime.
	 * @param key the Redis key
	 * @param type the type being created
	 * @return the transaction item
	 */
	private TransactWriteItem createMetaGuard(byte[] key, String type) {
		return TransactWriteItem.builder()
			.put(p -> p.tableName(this.tableName)
				.item(newMeta(key, type))
				.conditionExpression("attribute_not_exists(#pk)")
				.expressionAttributeNames(Map.of("#pk", "pk")))
			.build();
	}

	/**
	 * The meta-item operation of a mutation that grows an existing collection: a
	 * condition that the key is still there, still this type and not past its deadline —
	 * deliberately not a version compare, so replicas adding to one bucket do not
	 * conflict at all.
	 * @param key the Redis key
	 * @param type the type the caller saw
	 * @return the transaction item
	 */
	private TransactWriteItem liveMetaGuard(byte[] key, String type) {
		return TransactWriteItem.builder()
			.conditionCheck(c -> c.tableName(this.tableName)
				.key(metaKey(key))
				.conditionExpression(
						"attribute_exists(#pk) AND #t = :t AND (attribute_not_exists(#exp) OR #exp > :now)")
				.expressionAttributeNames(Map.of("#pk", "pk", "#t", "t", "#exp", "exp"))
				.expressionAttributeValues(
						Map.of(":t", AttributeValue.fromS(type), ":now", number(currentTimeMillis()))))
			.build();
	}

	/**
	 * Applies one guarded mutation: the guard and the first children in one transaction,
	 * any overflow in unconditional batches afterwards. Up to {@value #TRANSACTION_LIMIT}
	 * items the write is atomic; past that it no longer is, which is a stated consequence
	 * of the transaction ceiling rather than a surprise.
	 * @param what the operation, for messages
	 * @param guard the meta-item operation
	 * @param children the child writes
	 * @return {@code true} if it landed; {@code false} if the guard refused
	 */
	private boolean applyGuarded(String what, TransactWriteItem guard, List<ChildWrite> children) {
		List<TransactWriteItem> ops = new ArrayList<>();
		ops.add(guard);
		int inTransaction = Math.min(children.size(), TRANSACTION_LIMIT - 1);
		for (ChildWrite child : children.subList(0, inTransaction)) {
			ops.add(child.asTransactItem(this.tableName));
		}
		if (!transact(what, ops)) {
			return false;
		}
		if (inTransaction < children.size()) {
			batchWrite(what, children.subList(inTransaction, children.size()));
		}
		return true;
	}

	/**
	 * Runs one transaction, absorbing a guard's refusal into {@code false} so the caller
	 * re-reads and decides again. Anything else the transaction fails with is mapped or
	 * retried by {@link #call}.
	 * @param what the operation, for messages
	 * @param ops the transaction
	 * @return {@code true} if it landed; {@code false} if a condition refused it
	 */
	private boolean transact(String what, List<TransactWriteItem> ops) {
		try {
			call(what, () -> this.client.transactWriteItems(
					r -> r.transactItems(ops).overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout))));
			return true;
		}
		catch (TransactionCanceledException e) {
			return false;
		}
	}

	private boolean putConditional(String what, Map<String, AttributeValue> item, String condition,
			Map<String, String> names, Map<String, AttributeValue> values) {
		try {
			call(what, () -> this.client.putItem(r -> {
				r.tableName(this.tableName)
					.item(item)
					.conditionExpression(condition)
					.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout));
				if (!names.isEmpty()) {
					r.expressionAttributeNames(names);
				}
				if (!values.isEmpty()) {
					r.expressionAttributeValues(values);
				}
			}));
			return true;
		}
		catch (ConditionalCheckFailedException e) {
			return false;
		}
	}

	private boolean updateConditional(String what, Map<String, AttributeValue> key, String update, String condition,
			Map<String, String> names, Map<String, AttributeValue> values) {
		try {
			call(what, () -> this.client.updateItem(r -> {
				r.tableName(this.tableName)
					.key(key)
					.updateExpression(update)
					.conditionExpression(condition)
					.expressionAttributeNames(names)
					.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout));
				if (!values.isEmpty()) {
					r.expressionAttributeValues(values);
				}
			}));
			return true;
		}
		catch (ConditionalCheckFailedException e) {
			return false;
		}
	}

	private void batchWrite(String what, List<ChildWrite> writes) {
		for (int from = 0; from < writes.size(); from += BATCH_WRITE_LIMIT) {
			List<WriteRequest> chunk = new ArrayList<>();
			for (ChildWrite write : writes.subList(from, Math.min(from + BATCH_WRITE_LIMIT, writes.size()))) {
				chunk.add(write.asWriteRequest());
			}
			Map<String, List<WriteRequest>> remaining = Map.of(this.tableName, chunk);
			for (int attempt = 1; attempt <= this.maxAttempts && !remaining.isEmpty(); attempt++) {
				Map<String, List<WriteRequest>> ask = remaining;
				BatchWriteItemResponse response = call(what, () -> this.client.batchWriteItem(
						r -> r.requestItems(ask).overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout))));
				remaining = response.unprocessedItems();
				if (!remaining.isEmpty()) {
					backOff(attempt);
				}
			}
			if (!remaining.isEmpty()) {
				throw new DynamoDbBackendException(
						what + " kept coming back unprocessed after " + this.maxAttempts + " attempts");
			}
		}
	}

	/** One child item to write or remove alongside a guarded meta operation. */
	private record ChildWrite(Map<String, AttributeValue> item, boolean isDelete) {

		static ChildWrite put(Map<String, AttributeValue> item) {
			return new ChildWrite(item, false);
		}

		static ChildWrite delete(Map<String, AttributeValue> key) {
			return new ChildWrite(key, true);
		}

		TransactWriteItem asTransactItem(String tableName) {
			if (this.isDelete) {
				return TransactWriteItem.builder().delete(d -> d.tableName(tableName).key(this.item)).build();
			}
			return TransactWriteItem.builder().put(p -> p.tableName(tableName).item(this.item)).build();
		}

		WriteRequest asWriteRequest() {
			if (this.isDelete) {
				return WriteRequest.builder().deleteRequest(d -> d.key(this.item)).build();
			}
			return WriteRequest.builder().putRequest(p -> p.item(this.item)).build();
		}

	}

	/** A child item's address, for telling strays from what a mutation is writing. */
	private record ItemKey(String pk, String sk) {

		static ItemKey of(Map<String, AttributeValue> item) {
			return new ItemKey(Objects.requireNonNull(item.get("pk"), "pk").s(),
					Objects.requireNonNull(item.get("sk"), "sk").s());
		}

	}

	// --- requests ----------------------------------------------------------------------

	private List<Map<String, AttributeValue>> queryAll(String pk, @Nullable String skPrefix, boolean consistent) {
		return queryAll(pk, skPrefix, consistent, null);
	}

	/**
	 * Reads everything under one partition key, following the pages.
	 * @param pk the partition key
	 * @param skPrefix only sort keys with this prefix, or {@code null} for all
	 * @param consistent whether the read must be strongly consistent
	 * @param afterSk only sort keys past this one, or {@code null} from the start
	 * @return the items, in sort-key order
	 */
	private List<Map<String, AttributeValue>> queryAll(String pk, @Nullable String skPrefix, boolean consistent,
			@Nullable String afterSk) {
		List<Map<String, AttributeValue>> items = new ArrayList<>();
		Map<String, AttributeValue> startKey = null;
		do {
			Map<String, String> names = new HashMap<>(Map.of("#pk", "pk"));
			Map<String, AttributeValue> values = new HashMap<>(Map.of(":pk", AttributeValue.fromS(pk)));
			StringBuilder keyCondition = new StringBuilder("#pk = :pk");
			if (skPrefix != null) {
				keyCondition.append(" AND begins_with(#sk, :skPrefix)");
				names.put("#sk", "sk");
				values.put(":skPrefix", AttributeValue.fromS(skPrefix));
			}
			else if (afterSk != null && !afterSk.isEmpty()) {
				keyCondition.append(" AND #sk > :afterSk");
				names.put("#sk", "sk");
				values.put(":afterSk", AttributeValue.fromS(afterSk));
			}
			QueryRequest.Builder request = QueryRequest.builder()
				.tableName(this.tableName)
				.keyConditionExpression(keyCondition.toString())
				.expressionAttributeNames(names)
				.expressionAttributeValues(values)
				.consistentRead(consistent)
				.overrideConfiguration(o -> o.apiCallTimeout(this.requestTimeout));
			if (startKey != null) {
				request.exclusiveStartKey(startKey);
			}
			QueryRequest built = request.build();
			QueryResponse response = call("Reading " + pk, () -> this.client.query(built));
			items.addAll(response.items());
			startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
		}
		while (startKey != null);
		return items;
	}

	/**
	 * Sends one request, retrying what retrying can cure and naming what it cannot.
	 *
	 * <p>
	 * Throttling and transaction conflicts are retried with backoff — the emulator never
	 * throttles, so this path is proven by injected failures rather than by the ordinary
	 * suite. A refusal for size becomes the SPI's {@link ValueTooLargeException}: telling
	 * a client {@code internal error} sends it looking for a bug in the adapter, when
	 * what the application has to do is store less. A condition that failed is the
	 * caller's business and passes through untouched.
	 * @param <T> what the request returns
	 * @param what the operation, for messages
	 * @param request the request
	 * @return what DynamoDB answered
	 */
	private <T> T call(String what, Supplier<T> request) {
		SdkException last = null;
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			try {
				return request.get();
			}
			catch (ConditionalCheckFailedException e) {
				throw e;
			}
			catch (TransactionCanceledException e) {
				if (isTooLarge(e)) {
					throw tooLarge(what, e);
				}
				if (hasConditionFailure(e)) {
					throw e;
				}
				// every reason left is a conflict or throttling, which retrying cures
				last = e;
				backOff(attempt);
			}
			catch (ProvisionedThroughputExceededException e) {
				last = e;
				backOff(attempt);
			}
			catch (DynamoDbException e) {
				if (isTooLarge(e)) {
					throw tooLarge(what, e);
				}
				if (isRetryable(e)) {
					last = e;
					backOff(attempt);
					continue;
				}
				throw new DynamoDbBackendException(what + " was refused by DynamoDB", e);
			}
			catch (SdkException e) {
				throw new DynamoDbBackendException(what + " could not reach DynamoDB", e);
			}
		}
		throw new DynamoDbBackendException(what + " kept being refused after " + this.maxAttempts + " attempts",
				Objects.requireNonNull(last, "last"));
	}

	private static boolean isTooLarge(DynamoDbException e) {
		if (e instanceof TransactionCanceledException canceled) {
			for (CancellationReason reason : canceled.cancellationReasons()) {
				if (isTooLargeMessage(reason.message())) {
					return true;
				}
			}
		}
		return isTooLargeMessage(e.getMessage());
	}

	/**
	 * Refuses a value that cannot fit in one item before anything is written. Nothing is
	 * wrong and nothing is unreachable: the caller has to store less, and no retry can
	 * change that.
	 * @param what the operation, for the message
	 * @param pk the item's partition key
	 * @param sk the item's sort key
	 * @param valueBytes how many payload bytes the item would carry
	 */
	private static void requireFits(String what, String pk, String sk, int valueBytes) {
		long size = (long) pk.length() + sk.length() + valueBytes + ITEM_OVERHEAD_BYTES;
		if (size > MAX_ITEM_BYTES) {
			throw new ValueTooLargeException(
					what + " does not fit in one DynamoDB item: about " + size + " bytes against the 400 KB ceiling");
		}
	}

	private static boolean isTooLargeMessage(@Nullable String message) {
		return message != null && message.contains("exceeded the maximum allowed size");
	}

	private static ValueTooLargeException tooLarge(String what, DynamoDbException e) {
		return new ValueTooLargeException(
				what + " did not fit in one DynamoDB item (the ceiling is 400 KB): " + e.getMessage(), e);
	}

	private static boolean hasConditionFailure(TransactionCanceledException e) {
		for (CancellationReason reason : e.cancellationReasons()) {
			if ("ConditionalCheckFailed".equals(reason.code())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isRetryable(DynamoDbException e) {
		String code = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
		return "ThrottlingException".equals(code) || "InternalServerError".equals(code) || e.statusCode() >= 500;
	}

	/**
	 * Waits a little, and not the same little as anyone else, before trying a contended
	 * key again. Retrying immediately is what turns contention into a livelock.
	 * @param attempt which attempt just failed, counting from one
	 */
	private void backOff(int attempt) {
		long ceiling = Math.min(MAX_BACKOFF_MILLIS, 1L << Math.min(attempt, 6));
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new DynamoDbBackendException("Interrupted while waiting to retry a contended key", e);
		}
	}

	private DynamoDbBackendException contention(String operation, byte[] key) {
		return new DynamoDbBackendException(operation + " " + ByteArrayKey.of(key) + " gave up after "
				+ this.maxAttempts + " attempts because the key kept changing underneath it");
	}

	// --- keys --------------------------------------------------------------------------

	private String metaPk(byte[] key) {
		return "k/" + this.databaseIndex + "/" + B64.encodeToString(key);
	}

	private String memberPk(byte[] key, int shard) {
		return metaPk(key) + "/" + shard;
	}

	private Map<String, AttributeValue> memberItemKey(byte[] key, ByteArrayKey member, String skPrefix) {
		byte[] memberBytes = member.asBytes();
		return itemKey(memberPk(key, Math.floorMod(member.hashCode(), this.shards)),
				skPrefix + B64.encodeToString(memberBytes));
	}

	private String duePk(byte[] key) {
		return "d/" + this.databaseIndex + "/" + Math.floorMod(ByteArrayKey.of(key).hashCode(), this.shards);
	}

	private String logPk(long bucketSecond) {
		return "e/" + this.databaseIndex + "/" + bucketSecond;
	}

	private String leasePk() {
		return "s/" + this.databaseIndex;
	}

	private Map<String, AttributeValue> metaKey(byte[] key) {
		return itemKey(metaPk(key), META_SK);
	}

	private static Map<String, AttributeValue> itemKey(String pk, String sk) {
		Map<String, AttributeValue> item = new HashMap<>();
		item.put("pk", AttributeValue.fromS(pk));
		item.put("sk", AttributeValue.fromS(sk));
		return item;
	}

	private byte @Nullable [] keyOfMetaPk(String pk) {
		String prefix = "k/" + this.databaseIndex + "/";
		if (!pk.startsWith(prefix)) {
			return null;
		}
		String encoded = pk.substring(prefix.length());
		if (encoded.indexOf('/') >= 0) {
			return null; // a member partition, not a meta item's
		}
		return B64_DECODER.decode(encoded);
	}

	private static String sk(Map<String, AttributeValue> item) {
		return Objects.requireNonNull(item.get("sk"), "sk").s();
	}

	private static AttributeValue number(long value) {
		return AttributeValue.fromN(Long.toString(value));
	}

	private static AttributeValue number(double value) {
		// DynamoDB numbers are decimal text; Double.toString's exponent form is not.
		return AttributeValue.fromN(BigDecimal.valueOf(value).toPlainString());
	}

	private static Set<ByteArrayKey> distinct(List<byte[]> members) {
		Set<ByteArrayKey> distinct = new LinkedHashSet<>();
		for (byte[] member : members) {
			distinct.add(ByteArrayKey.of(member));
		}
		return distinct;
	}

	/**
	 * Builder for {@link DynamoDbKeyValueStore}.
	 *
	 * <p>
	 * The client is the one thing a caller has to give — built and signed however the
	 * deployment signs its AWS calls, or pointed at an emulator by an endpoint override.
	 * Everything else has a default that suits a session store; the shard count is the
	 * one setting that must never change over a table's life.
	 */
	public static final class Builder {

		private @Nullable DynamoDbClient client;

		private String tableName = "redis-adapter";

		private int databaseIndex;

		private int shards = 4;

		private boolean createTable = true;

		private boolean sweeperEnabled = true;

		private Duration pollInterval = Duration.ofMillis(100);

		private Duration cursorLag = Duration.ofMillis(500);

		private Duration sweepInterval = Duration.ofSeconds(1);

		private Duration logRetention = Duration.ofSeconds(60);

		private Duration requestTimeout = Duration.ofSeconds(5);

		private int maxAttempts = 10;

		private LongSupplier clock = System::currentTimeMillis;

		private Builder() {
		}

		/**
		 * Sets the client every request goes through. The store never closes it: it was
		 * built by the caller and it is the caller's.
		 * @param client the client
		 * @return this builder
		 */
		public Builder client(DynamoDbClient client) {
			this.client = Objects.requireNonNull(client, "client");
			return this;
		}

		/**
		 * Sets the table the sessions live in.
		 * @param tableName the table name
		 * @return this builder
		 */
		public Builder tableName(String tableName) {
			if (tableName.isBlank()) {
				throw new IllegalArgumentException("the table name must not be blank");
			}
			this.tableName = tableName;
			return this;
		}

		/**
		 * Sets the database this store serves. It prefixes every partition key, which is
		 * what makes the databases independent keyspaces in one table.
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
		 * Sets how many partitions a set's members and the deadline index spread over.
		 * DynamoDB caps one partition at 1,000 writes per second whatever the table's
		 * capacity, and every session expiring in the same minute joins one bucket, so
		 * this is that bucket's write ceiling in thousands per second. It is fixed for
		 * the life of a table: members stay where the shard count that wrote them put
		 * them.
		 * @param shards the shard count, at least one
		 * @return this builder
		 */
		public Builder shards(int shards) {
			if (shards < 1) {
				throw new IllegalArgumentException("shards must be at least 1: " + shards);
			}
			this.shards = shards;
			return this;
		}

		/**
		 * Sets whether the store creates the table — on-demand billing, the deadline
		 * index, the TTL backstop — when it is absent.
		 * @param createTable whether to create the table
		 * @return this builder
		 */
		public Builder createTable(boolean createTable) {
			this.createTable = createTable;
			return this;
		}

		/**
		 * Sets whether this store runs the sweeper that announces the keys nobody
		 * touches. Off is for tests that need to hold expiry still; a deployment leaves
		 * it on, and the lease elects one holder per database however many replicas run.
		 * @param sweeperEnabled whether to sweep
		 * @return this builder
		 */
		public Builder sweeperEnabled(boolean sweeperEnabled) {
			this.sweeperEnabled = sweeperEnabled;
			return this;
		}

		/**
		 * Sets how often the key-event log is polled. This is half of how long an event
		 * takes to arrive — and a standing charge: an idle poll is a billed read, every
		 * interval, per replica and database.
		 * @param pollInterval the poll interval
		 * @return this builder
		 */
		public Builder pollInterval(Duration pollInterval) {
			this.pollInterval = requirePositive("pollInterval", pollInterval);
			return this;
		}

		/**
		 * Sets how far the log cursor stays behind wall-clock. An entry stamped behind
		 * the cursor is never seen, so this must outlast the fleet's clock skew plus a
		 * write's latency; it is the other half of an event's arrival time.
		 * @param cursorLag the lag
		 * @return this builder
		 */
		public Builder cursorLag(Duration cursorLag) {
			this.cursorLag = requirePositive("cursorLag", cursorLag);
			return this;
		}

		/**
		 * Sets how long between sweeps, which is the longest an expired key nobody
		 * touches can sit unannounced — plus how often an idle replica pays for the lease
		 * attempt and the index reads.
		 * @param sweepInterval the sweep interval
		 * @return this builder
		 */
		public Builder sweepInterval(Duration sweepInterval) {
			this.sweepInterval = requirePositive("sweepInterval", sweepInterval);
			return this;
		}

		/**
		 * Sets how long read log entries are kept before the sweeper trims them. It has
		 * to comfortably outlast the cursor lag, or a slow replica resumes past a hole.
		 * @param logRetention the retention
		 * @return this builder
		 */
		public Builder logRetention(Duration logRetention) {
			this.logRetention = requirePositive("logRetention", logRetention);
			return this;
		}

		/**
		 * Sets how long to wait for DynamoDB to answer one request. It bounds how long a
		 * Redis command can hang: a client waiting on a session is better told that
		 * something failed than left waiting.
		 * @param requestTimeout the per-request timeout
		 * @return this builder
		 */
		public Builder requestTimeout(Duration requestTimeout) {
			this.requestTimeout = requirePositive("requestTimeout", requestTimeout);
			return this;
		}

		/**
		 * Sets how many times an operation retries a key that changed underneath it, or a
		 * request DynamoDB throttled, before giving up.
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
		 * Builds the store, makes sure the table exists, and starts the log poller and —
		 * unless disabled — the sweeper.
		 *
		 * <p>
		 * A table that cannot be reached here is logged rather than thrown: every backend
		 * is created while the application starts, whether or not it is the one selected,
		 * so a DynamoDB that is briefly away must not take the server with it. The
		 * sweeper keeps trying to set the table up; until it succeeds, operations fail
		 * with what DynamoDB says.
		 * @return a new store
		 */
		public DynamoDbKeyValueStore build() {
			if (this.cursorLag.compareTo(this.logRetention) >= 0) {
				throw new IllegalArgumentException("logRetention (" + this.logRetention
						+ ") must comfortably outlast cursorLag (" + this.cursorLag + ")");
			}
			DynamoDbKeyValueStore store = new DynamoDbKeyValueStore(this);
			try {
				store.ensureTable();
			}
			catch (RuntimeException e) {
				logger.warn("Could not make sure table {} exists; operations will fail until it can be reached",
						store.tableName, e);
			}
			Thread poller = Thread.ofVirtual()
				.name("dynamodb-kvs-log-" + store.tableName + "-" + store.databaseIndex)
				.unstarted(store::poll);
			store.poller = poller;
			poller.start();
			if (this.sweeperEnabled) {
				Thread sweeper = Thread.ofVirtual()
					.name("dynamodb-kvs-sweeper-" + store.tableName + "-" + store.databaseIndex)
					.unstarted(store::sweep);
				store.sweeper = sweeper;
				sweeper.start();
			}
			return store;
		}

	}

}
