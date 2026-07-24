package am.ik.redis.adapter.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Redis sorted-set value: an ordered map from opaque member to score.
 *
 * <p>
 * Members ({@link ByteArrayKey}) are compared by value, so adding the same bytes twice
 * moves that one member to a new score rather than adding a second one. They are opaque —
 * the sorted set that carries session expirations holds serialized session ids — so they
 * are never decoded. A score is a {@code double}, which for that sorted set is the epoch
 * millisecond a session is due to expire at.
 *
 * <p>
 * The map is copied into an unmodifiable, insertion-ordered map on construction, so an
 * instance is a stable snapshot. That order is <strong>not</strong> the sorted-set order:
 * a sorted set runs by score, and by member bytes among members of equal score, and it is
 * the reader that puts it in that order (see {@code ZREVRANGEBYSCORE} in the command
 * layer). Keeping the snapshot in insertion order costs a backend nothing on every write
 * and leaves one definition of the ordering, in the command that depends on it.
 *
 * @param scores the member-to-score map (copied defensively; iteration order preserved)
 */
public record ZSetValue(Map<ByteArrayKey, Double> scores) implements RedisValue {

	public ZSetValue {
		scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
	}
}
