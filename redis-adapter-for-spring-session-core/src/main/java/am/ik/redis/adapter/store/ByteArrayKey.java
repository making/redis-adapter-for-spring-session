package am.ik.redis.adapter.store;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * Value-equal, immutable wrapper over a {@code byte[]}.
 *
 * <p>
 * A raw {@code byte[]} uses identity {@code equals}/{@code hashCode}, so it cannot be
 * used as a map key or set member when the intent is value equality. This wrapper
 * compares by content and is therefore the key type used everywhere the store must key by
 * bytes: top-level keys, hash-field names and set members.
 *
 * <p>
 * The wrapped bytes are copied on construction and copied again when handed back through
 * {@link #asBytes()}, so an instance is fully insulated from external mutation and safe
 * to use as a stable map key. Instances are immutable and thread-safe.
 *
 * <p>
 * Keys are also ordered, by comparing their bytes as <em>unsigned</em> values, which is
 * the order Redis puts two equally scored members of a sorted set in. The ordering is
 * consistent with {@link #equals(Object)}.
 */
public final class ByteArrayKey implements Comparable<ByteArrayKey> {

	private final byte[] bytes;

	private final int hash;

	private ByteArrayKey(byte[] bytes) {
		this.bytes = bytes;
		this.hash = Arrays.hashCode(bytes);
	}

	/**
	 * Creates a key from the given bytes. The array is defensively copied, so later
	 * mutation of the caller's array does not affect this key.
	 * @param bytes the raw bytes (not retained)
	 * @return a value-equal key over a private copy of {@code bytes}
	 */
	public static ByteArrayKey of(byte[] bytes) {
		return new ByteArrayKey(bytes.clone());
	}

	/**
	 * Returns a fresh copy of the wrapped bytes. Callers may mutate the returned array
	 * without affecting this key.
	 * @return a defensive copy of the wrapped bytes
	 */
	public byte[] asBytes() {
		return this.bytes.clone();
	}

	/**
	 * Returns the number of bytes wrapped.
	 * @return the byte length
	 */
	public int length() {
		return this.bytes.length;
	}

	/**
	 * Compares two keys by their bytes, taken as unsigned values, the shorter key coming
	 * first when one is a prefix of the other.
	 * @param other the key to compare against
	 * @return a negative number, zero or a positive number as this key sorts before, the
	 * same as, or after {@code other}
	 */
	@Override
	public int compareTo(ByteArrayKey other) {
		return Arrays.compareUnsigned(this.bytes, other.bytes);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) {
			return true;
		}
		return o instanceof ByteArrayKey other && this.hash == other.hash && Arrays.equals(this.bytes, other.bytes);
	}

	@Override
	public int hashCode() {
		return this.hash;
	}

	/**
	 * Returns a debug rendering that escapes non-printable bytes. Set members are opaque
	 * (JDK-serialized) blobs, so this never assumes the content is text; it is intended
	 * for logging and test diagnostics only.
	 * @return an escaped, printable rendering of the bytes
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("ByteArrayKey[");
		for (byte b : this.bytes) {
			int c = b & 0xFF;
			if (c >= 0x20 && c < 0x7F) {
				sb.append((char) c);
			}
			else {
				sb.append("\\x").append(Character.forDigit((c >> 4) & 0xF, 16)).append(Character.forDigit(c & 0xF, 16));
			}
		}
		return sb.append(']').toString();
	}

}
