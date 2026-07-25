/**
 * etcd backend: {@code EtcdKeyValueStore}, a {@code KeyValueStore} that keeps the
 * sessions in an etcd cluster.
 *
 * <p>
 * Like the in-memory reference backend, this module lives outside the core and depends
 * only on the {@code am.ik.redis.adapter.store} SPI. Unlike it, the store it keeps is
 * <strong>shared</strong>: several adapter replicas serve the same sessions, those
 * sessions survive every adapter restarting, and a key one replica expires is announced
 * to the clients subscribed to all the others — which is what {@code KeyEventListener}
 * exists for and what a horizontally scaled deployment needs.
 *
 * <p>
 * It talks to etcd as JSON over HTTP, through the gRPC gateway every etcd serves on its
 * client port, so the runtime dependencies stay what the core's are: slf4j and jspecify.
 * No gRPC stack, no protobuf and no Netty reach the server that hosts it.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.etcd;

import org.jspecify.annotations.NullMarked;
