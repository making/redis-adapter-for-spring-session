/**
 * Pub/Sub layer: the process-wide subscription registry and keyspace-notification
 * emitter.
 *
 * <p>
 * This package holds the channel and pattern subscriptions shared across client
 * connections, the glob matcher used for pattern subscriptions, and the emitter that
 * turns key events (delete, expiry) into {@code __keyevent@<db>__:*} keyspace
 * notifications that Spring Session's indexed mode relies on.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.pubsub;

import org.jspecify.annotations.NullMarked;
