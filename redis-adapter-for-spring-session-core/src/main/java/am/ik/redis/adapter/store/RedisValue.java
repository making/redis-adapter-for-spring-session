package am.ik.redis.adapter.store;

/**
 * A typed value stored under a key.
 *
 * <p>
 * Redis keys are strongly typed: a key holds exactly one of a string, a hash, a set (or a
 * sorted set, added later). This sealed hierarchy models the subset the adapter needs.
 * Payload bytes (string content, hash-field values, set members) are
 * <strong>opaque</strong> — they are JDK-serialized blobs that must be stored and
 * returned byte-for-byte and never interpreted. Only key names and hash-field names are
 * UTF-8 text, and even those are kept as bytes here.
 *
 * <p>
 * Pattern matching over this type lets the command layer branch on the stored type, for
 * example to raise a wrong-type error:
 *
 * <pre>{@code
 * switch (store.get(key)) {
 *     case null -> // absent
 *     case StringValue s -> // ...
 *     case HashValue h -> // ...
 *     case SetValue set -> // ...
 * }
 * }</pre>
 *
 * Implementations are immutable value types.
 */
public sealed interface RedisValue permits StringValue, HashValue, SetValue {

}
