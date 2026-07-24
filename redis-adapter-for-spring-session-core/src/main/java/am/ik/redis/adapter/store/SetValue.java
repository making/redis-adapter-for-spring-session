package am.ik.redis.adapter.store;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A Redis set value: a collection of distinct opaque members.
 *
 * <p>
 * Members ({@link ByteArrayKey}) are compared by value, not identity, so adding the same
 * bytes twice is idempotent. Members are opaque (for the principal index set they are
 * JDK-serialized session ids), so they are never decoded.
 *
 * <p>
 * The members are copied into an unmodifiable, insertion-ordered set on construction, so
 * an instance is a stable snapshot.
 *
 * @param members the set members (copied defensively; iteration order preserved)
 */
public record SetValue(Set<ByteArrayKey> members) implements RedisValue {

	public SetValue {
		members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
	}
}
