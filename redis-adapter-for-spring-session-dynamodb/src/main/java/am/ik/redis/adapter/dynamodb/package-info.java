/**
 * DynamoDB backend: {@code DynamoDbKeyValueStore}, a {@code KeyValueStore} that keeps the
 * sessions in one DynamoDB table.
 *
 * <p>
 * Like the etcd backend, this module lives outside the core, depends on the
 * {@code am.ik.redis.adapter.store} SPI, and keeps a <strong>shared</strong> store:
 * several adapter replicas serve the same sessions, the sessions survive every adapter
 * restarting, and a key one replica removes is announced to the clients subscribed to all
 * the others — here through a polled event log written in the same transaction as the
 * removal, because DynamoDB has no watch and Streams cannot serve as one
 * ({@code .docs/design/architecture.md} §12.5).
 *
 * <p>
 * It talks to DynamoDB through the AWS SDK v2 over the JDK's {@code HttpURLConnection}
 * ({@code url-connection-client}); no Netty, Jackson or Guava reaches the server that
 * hosts it. The {@code DynamoDbClient} is handed in rather than built here, so where
 * DynamoDB is and how requests are signed stays the caller's business.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.dynamodb;

import org.jspecify.annotations.NullMarked;
