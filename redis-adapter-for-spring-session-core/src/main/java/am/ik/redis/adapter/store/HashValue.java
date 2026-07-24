package am.ik.redis.adapter.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Redis hash value: an ordered map from field name to opaque field value.
 *
 * <p>
 * Field names ({@link ByteArrayKey}) are UTF-8 text on the wire (for example
 * {@code creationTime}, {@code sessionAttr:foo}), but are kept as bytes so nothing is
 * ever decoded. Field values are opaque JDK-serialized blobs.
 *
 * <p>
 * The map is copied into an unmodifiable, insertion-ordered map on construction, so an
 * instance is a stable snapshot. The value byte arrays are shared by reference and must
 * be treated as <strong>read-only</strong> by callers — the RESP layer only ever reads
 * them to write to the socket and never mutates or reuses those buffers.
 *
 * @param fields the field-to-value map (copied defensively; iteration order preserved)
 */
public record HashValue(Map<ByteArrayKey, byte[]> fields) implements RedisValue {

	public HashValue {
		fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}
}
