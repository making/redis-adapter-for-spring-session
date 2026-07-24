/**
 * Storage layer: the {@code KeyValueStore} SPI.
 *
 * <p>
 * This package defines the single pluggable seam of the adapter, and nothing else. A
 * backend implements the {@code KeyValueStore} contract (typed values, per-key TTL,
 * passive and active expiration, key-event callbacks) from its own module, which depends
 * on this one and nothing more; the bundled in-memory reference backend
 * ({@code am.ik.redis.adapter.inmemory}) is deliberately a peer of any external backend
 * rather than a privileged part of the core.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.store;

import org.jspecify.annotations.NullMarked;
