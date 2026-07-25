package am.ik.redis.adapter.etcd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import am.ik.redis.adapter.store.ByteArrayKey;
import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.ZSetValue;
import org.jspecify.annotations.Nullable;

/**
 * What one etcd key holds: a typed value, and the deadline it dies at.
 *
 * <p>
 * etcd stores opaque bytes under opaque keys and knows nothing of Redis types, so the
 * type travels with the value. The expiry travels with it too, even though the key is
 * also attached to an etcd lease, and the two are there for different jobs: the lease
 * removes the key in bounded time when nobody comes back to it (etcd leases are whole
 * seconds, rounded up so a key never dies early), while the deadline here is exact to the
 * millisecond and is what every read compares against. That is what lets a key be
 * logically gone the millisecond it should be, while etcd is still a second away from
 * collecting it.
 *
 * <h2>Tombstones</h2> A tombstone is an envelope with no value: a key that is logically
 * absent and whose removal <strong>announces nothing</strong>. Two operations need that.
 * {@code RENAME} must move a value without its source looking deleted, and an emptied set
 * or sorted set removes its key without a {@code del} event. Neither can be expressed by
 * deleting the key, because a delete is exactly what every adapter replica watching etcd
 * would report. So the key is first written as a tombstone and then removed; whoever sees
 * the removal sees that what went was already nothing, and stays quiet. Every tombstone
 * carries a short lease, so one left behind by a process that died mid-rename disappears
 * on its own.
 *
 * <h2>Format</h2> A format byte, a type byte, the deadline, then the value. Lengths are
 * 32-bit, which is the length a Redis string, hash field or set member can have anyway.
 * The format byte is there so a future change can be recognized rather than guessed at;
 * anything else is refused rather than read as something it is not.
 *
 * @param value the typed value, or {@code null} for a tombstone
 * @param expireAtMillis the absolute deadline in epoch milliseconds, or
 * {@link #NO_EXPIRY} for a key that does not expire
 */
record Envelope(@Nullable RedisValue value, long expireAtMillis) {

	/** Sentinel deadline meaning "never expires". */
	static final long NO_EXPIRY = Long.MAX_VALUE;

	private static final byte FORMAT = 1;

	private static final byte TOMBSTONE = 0;

	private static final byte STRING = 1;

	private static final byte HASH = 2;

	private static final byte SET = 3;

	private static final byte ZSET = 4;

	/**
	 * Returns an envelope holding a value.
	 * @param value the typed value
	 * @param expireAtMillis the absolute deadline, or {@link #NO_EXPIRY}
	 * @return the envelope
	 */
	static Envelope of(RedisValue value, long expireAtMillis) {
		return new Envelope(value, expireAtMillis);
	}

	/**
	 * Returns a tombstone: a key that is logically absent and announces nothing when it
	 * goes.
	 * @return the tombstone
	 */
	static Envelope tombstone() {
		return new Envelope(null, NO_EXPIRY);
	}

	/**
	 * Reports whether this is a tombstone, which every read treats as an absent key and
	 * the watcher passes over in silence.
	 * @return {@code true} if there is no value
	 */
	boolean isTombstone() {
		return this.value == null;
	}

	/**
	 * Reports whether the deadline has passed.
	 * @param now the current time in epoch milliseconds
	 * @return {@code true} if the key is logically gone
	 */
	boolean isExpired(long now) {
		return this.expireAtMillis <= now;
	}

	/**
	 * Returns the value, for an envelope known not to be a tombstone.
	 * @return the typed value
	 * @throws IllegalStateException if this is a tombstone
	 */
	RedisValue requiredValue() {
		RedisValue value = this.value;
		if (value == null) {
			throw new IllegalStateException("A tombstone holds no value");
		}
		return value;
	}

	/**
	 * Returns the same value under a new deadline.
	 * @param expireAtMillis the absolute deadline, or {@link #NO_EXPIRY}
	 * @return the new envelope
	 */
	Envelope withExpireAt(long expireAtMillis) {
		return new Envelope(this.value, expireAtMillis);
	}

	/**
	 * Encodes this envelope as the bytes one etcd key holds.
	 * @return the encoded bytes
	 */
	byte[] encode() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeByte(FORMAT);
			out.writeByte(type());
			out.writeLong(this.expireAtMillis);
			switch (this.value) {
				case null -> {
				}
				case StringValue string -> out.write(string.value());
				case HashValue hash -> {
					out.writeInt(hash.fields().size());
					for (Map.Entry<ByteArrayKey, byte[]> field : hash.fields().entrySet()) {
						writeBytes(out, field.getKey().asBytes());
						writeBytes(out, field.getValue());
					}
				}
				case SetValue set -> {
					out.writeInt(set.members().size());
					for (ByteArrayKey member : set.members()) {
						writeBytes(out, member.asBytes());
					}
				}
				case ZSetValue zset -> {
					out.writeInt(zset.scores().size());
					for (Map.Entry<ByteArrayKey, Double> scored : zset.scores().entrySet()) {
						writeBytes(out, scored.getKey().asBytes());
						out.writeDouble(scored.getValue());
					}
				}
			}
		}
		catch (IOException e) {
			// A ByteArrayOutputStream does not fail; this keeps the checked exception out
			// of every caller.
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/**
	 * Decodes what an etcd key holds.
	 * @param encoded the stored bytes
	 * @return the envelope
	 * @throws EtcdException if the bytes were not written by {@link #encode()}
	 */
	static Envelope decode(byte[] encoded) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
			byte format = in.readByte();
			if (format != FORMAT) {
				throw new EtcdException("Unknown value format " + format
						+ "; the keyspace was written by a different version of this backend");
			}
			byte type = in.readByte();
			long expireAtMillis = in.readLong();
			RedisValue value = switch (type) {
				case TOMBSTONE -> null;
				case STRING -> new StringValue(in.readAllBytes());
				case HASH -> {
					int count = in.readInt();
					Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
					for (int i = 0; i < count; i++) {
						fields.put(ByteArrayKey.of(readBytes(in)), readBytes(in));
					}
					yield new HashValue(fields);
				}
				case SET -> {
					int count = in.readInt();
					Set<ByteArrayKey> members = new LinkedHashSet<>();
					for (int i = 0; i < count; i++) {
						members.add(ByteArrayKey.of(readBytes(in)));
					}
					yield new SetValue(members);
				}
				case ZSET -> {
					int count = in.readInt();
					Map<ByteArrayKey, Double> scores = new LinkedHashMap<>();
					for (int i = 0; i < count; i++) {
						scores.put(ByteArrayKey.of(readBytes(in)), in.readDouble());
					}
					yield new ZSetValue(scores);
				}
				default -> throw new EtcdException("Unknown value type " + type);
			};
			return new Envelope(value, expireAtMillis);
		}
		catch (IOException e) {
			throw new EtcdException("Could not read a stored value", e);
		}
	}

	private byte type() {
		return switch (this.value) {
			case null -> TOMBSTONE;
			case StringValue ignored -> STRING;
			case HashValue ignored -> HASH;
			case SetValue ignored -> SET;
			case ZSetValue ignored -> ZSET;
		};
	}

	private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
		out.writeInt(value.length);
		out.write(value);
	}

	private static byte[] readBytes(DataInputStream in) throws IOException {
		byte[] value = new byte[in.readInt()];
		in.readFully(value);
		return value;
	}

	/**
	 * Returns which key event the removal of a key holding this envelope is, as
	 * {@link KeyEventListener} names them.
	 * @param now the current time in epoch milliseconds
	 * @return the event, or {@code null} if the removal announces nothing
	 */
	@Nullable Removal removalEvent(long now) {
		if (isTombstone()) {
			return null;
		}
		return isExpired(now) ? Removal.EXPIRED : Removal.DELETED;
	}

	/** Why a key was removed, which is what a keyspace notification carries. */
	enum Removal {

		/** Its deadline had passed: {@link KeyEventListener#onExpired}. */
		EXPIRED,

		/** Something removed a live key: {@link KeyEventListener#onDeleted}. */
		DELETED

	}

}
