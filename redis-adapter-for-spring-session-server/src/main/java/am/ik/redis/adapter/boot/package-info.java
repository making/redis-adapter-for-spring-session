/**
 * Spring Boot server that wraps the dependency-free adapter core.
 *
 * <p>
 * This module intentionally depends on Spring Boot: the "no external dependencies" rule
 * applies to the reusable core, not to the runnable server. It will bind configuration
 * properties (bind address, port, backend selection, default TTL, optional auth, TLS
 * bundle), start and stop the core {@code RedisAdapterServer} through a lifecycle bean,
 * and expose actuator health and metrics. This base package currently holds only the
 * application entry point.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.boot;

import org.jspecify.annotations.NullMarked;
