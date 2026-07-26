/**
 * The adapter server built around the etcd backend.
 *
 * <p>
 * It is the server module's auto-configuration plus one backend: the properties that
 * backend is tuned with, the factory bean that hands out a store per database, and the
 * main class. Nothing about the server itself is repeated here, and nothing here is known
 * to the server.
 *
 * <p>
 * The sessions live in an etcd cluster, so several adapters can serve the same ones and
 * they outlive all of them. This is the server for a horizontally scaled deployment;
 * {@code redis-adapter-for-spring-session-server-inmemory} is the one for a single
 * instance.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.boot.etcd;

import org.jspecify.annotations.NullMarked;
