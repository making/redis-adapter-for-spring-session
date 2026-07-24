package am.ik.redis.adapter.store;

/**
 * A Redis string value: a single opaque byte payload.
 *
 * <p>
 * The payload may be empty ({@code byte[0]}), which is exactly what {@code APPEND key ""}
 * on an absent key materializes. The bytes are opaque and must never be interpreted; they
 * are not defensively copied here for efficiency, so the store owns the array and callers
 * must treat {@link #value()} as read-only.
 *
 * @param value the opaque string payload (never {@code null}, possibly empty); treated as
 * read-only
 */
public record StringValue(byte[] value) implements RedisValue {
}
