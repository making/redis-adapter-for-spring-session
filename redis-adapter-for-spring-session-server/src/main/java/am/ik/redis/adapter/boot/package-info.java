/**
 * Everything the Spring Boot server around the dependency-free adapter core is, except
 * the backend it stores sessions in.
 *
 * <p>
 * This module intentionally depends on Spring Boot: the "no external dependencies" rule
 * applies to the reusable core, not to the runnable server. It binds the configuration
 * properties (bind address, port, optional password, database count, TLS bundle), creates
 * one backend per database out of the single {@link KeyValueStoreFactory} on the class
 * path, starts and stops the core {@code RedisAdapterServer} through a lifecycle bean,
 * and exposes actuator health and metrics.
 *
 * <p>
 * There is no runnable application here and no backend. A server is a module of its own
 * per store — {@code redis-adapter-for-spring-session-server-<backend>} — which is what
 * lets a store this project cannot ship, because it is not open source, be served by a
 * module built somewhere else against nothing but this one.
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
