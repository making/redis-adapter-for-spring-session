/**
 * Spring Boot server that wraps the dependency-free adapter core.
 *
 * <p>
 * This module intentionally depends on Spring Boot: the "no external dependencies" rule
 * applies to the reusable core, not to the runnable server. It binds the configuration
 * properties (bind address, port, backend selection, optional password, database count,
 * TLS bundle), creates one backend per database, starts and stops the core
 * {@code RedisAdapterServer} through a lifecycle bean, and exposes actuator health and
 * metrics.
 *
 * <p>
 * Nothing here speaks about sessions. The server answers RESP, and it is the application
 * on the other end of the socket that runs Spring Session; that application needs neither
 * this module nor any other part of this project on its classpath.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.boot;

import org.jspecify.annotations.NullMarked;
