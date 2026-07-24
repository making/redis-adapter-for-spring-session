/**
 * Server layer: the TCP accept loop and per-connection handling.
 *
 * <p>
 * This package contains the {@code RedisAdapterServer} accept loop and the
 * {@code ClientConnection} that reads, dispatches and writes on one virtual thread per
 * TCP connection, holding the per-connection database selection and subscription state.
 * The server accepts an injected {@code javax.net.ServerSocketFactory} so that TLS can be
 * layered on without the core depending on Spring or certificate configuration.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.server;

import org.jspecify.annotations.NullMarked;
