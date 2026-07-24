/**
 * Protocol layer: the RESP reader and writer plus the RESP element model.
 *
 * <p>
 * This package parses inbound RESP request arrays and encodes outbound replies and push
 * frames for RESP2 and RESP3. It knows nothing about Redis command semantics; it only
 * moves bytes to and from the typed RESP element model consumed by the command layer.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.protocol;

import org.jspecify.annotations.NullMarked;
