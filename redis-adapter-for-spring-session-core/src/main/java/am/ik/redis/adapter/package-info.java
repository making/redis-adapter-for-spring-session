/**
 * Dependency-free core of the Redis adapter for Spring Session.
 *
 * <p>
 * The adapter is a standalone server that speaks the Redis wire protocol (RESP) over TCP
 * so that a Spring application using stock Spring Session Data Redis and a stock Redis
 * client can store its HTTP sessions in an arbitrary key-value store. This base package
 * groups the layered sub-packages described in the architecture design: {@code store},
 * {@code protocol}, {@code command}, {@code pubsub} and {@code server}.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter;

import org.jspecify.annotations.NullMarked;
