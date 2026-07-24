/**
 * Storage layer: the {@code KeyValueStore} SPI and its in-memory implementation.
 *
 * <p>
 * This package defines the single pluggable seam of the adapter. A backend implements the
 * {@code KeyValueStore} contract (typed values, per-key TTL, passive and active
 * expiration, key-event callbacks); the bundled in-memory implementation is backed by a
 * {@code ConcurrentHashMap} and is intended for development, single-instance and test
 * usage.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.store;

import org.jspecify.annotations.NullMarked;
