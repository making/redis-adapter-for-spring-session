/**
 * The adapter server built around the in-memory backend.
 *
 * <p>
 * It is the server module's auto-configuration plus one backend: the properties that
 * backend is tuned with, the factory bean that hands out a store per database, and the
 * main class. That is all a server module is, whichever store is underneath, which is why
 * this one doubles as the worked example for a store this project does not ship.
 *
 * <p>
 * The sessions live in this process, so they do not survive a restart and are not shared
 * between replicas. It is the server for development, tests and a single instance;
 * {@code redis-adapter-for-spring-session-server-etcd} is the one for more than one.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.boot.inmemory;

import org.jspecify.annotations.NullMarked;
