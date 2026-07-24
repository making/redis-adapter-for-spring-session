/**
 * In-memory reference backend: {@code InMemoryKeyValueStore}, a {@code KeyValueStore}
 * backed by a {@code ConcurrentHashMap}.
 *
 * <p>
 * This backend lives outside the core module and depends only on the
 * {@code am.ik.redis.adapter.store} SPI, exactly like a future external backend, so the
 * pluggable seam is exercised for real rather than trusted. It is inherently single-node
 * (each instance owns its own map) and is therefore the development, single-instance and
 * test backend.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.inmemory;

import org.jspecify.annotations.NullMarked;
