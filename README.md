# Redis Adapter for Spring Session

A standalone server that speaks the Redis wire protocol (RESP) and keeps what it is told in a
pluggable key-value store. A Spring application points stock
[Spring Session Data Redis](https://docs.spring.io/spring-session/reference/) at it, and its HTTP
sessions land in that store instead of in Redis.

The application keeps Spring Boot's own session auto-configuration and its ordinary Redis client,
and configures everything with properties. Only the connection target changes.

```text
  Spring Boot application                        Redis adapter server
+---------------------------+                +-----------------------------+
| spring-boot-starter-      |                | RESP codec + command layer  |
|   session-data-redis      |  RESP over TCP | pub/sub + keyspace events   |
| Lettuce                   |--------------->| authentication + TLS        |
+---------------------------+   (or TLS)     +--------------+--------------+
                                                            |
                                             KeyValueStore  |  (one SPI)
                                                            v
                                              +---------------------------+
                                              | in-memory backend, etcd,  |
                                              | or a backend you write    |
                                              +---------------------------+
```

## Contents

- [When to use it](#when-to-use-it)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Using it from an application](#using-it-from-an-application)
- [Running the server](#running-the-server)
- [Backends](#backends)
- [What is implemented](#what-is-implemented)
- [Writing a backend](#writing-a-backend)
- [Example applications](#example-applications)
- [Limitations and non-goals](#limitations-and-non-goals)
- [Building from source](#building-from-source)
- [Design documents](#design-documents)

## When to use it

Use it when you want Spring Session's HTTP sessions to live in a store that is not Redis, without
that store's driver, credentials or configuration reaching the application's classpath. The
application talks Redis; where the sessions actually end up is the adapter's business, and changing
it never touches the application.

Do not use it when:

- you already run Redis and are content with it. The adapter would add a process and a network hop
  and give you nothing;
- your application is reactive (WebFlux). Only the servlet, blocking mode is supported;
- you need Redis for anything besides Spring Session. Only the commands Spring Session issues are
  implemented, and no more.

Two things are worth knowing before you start:

- **The default backend is in-memory.** It keeps the sessions in the adapter process, so they are
  gone when it restarts and are not shared with a second adapter. That is the development and
  single-instance backend, and it is what makes the server runnable with no configuration at all.
  Running several adapters in front of the same sessions needs a backend that is itself shared:
  [etcd](#etcd) is bundled, and anything else is a module you write.
- **The adapter itself holds no session state.** Everything it is asked to remember goes to the
  backend, so replicas scale as far as the backend does.

## Requirements

- Server: Java 25 or later.
- Application: nothing from this project. Any Spring Boot application that can use Spring Session
  Data Redis will do, whatever JDK it runs on.

## Quick start

### 1. Start the adapter

```bash
java -jar redis-adapter-for-spring-session-server-<version>-exec.jar
```

It listens on port 6379, serves one database, asks for no password, and keeps the sessions in
memory.

### 2. Give the application Spring Session

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-session-data-redis</artifactId>
</dependency>
```

### 3. Point it at the adapter

<!-- snippet:app-connection -->
```properties
spring.data.redis.host=adapter.example.com
spring.data.redis.port=6379
```

That is the whole integration. Spring Boot configures Spring Session as soon as the starter is on
the class path, so the application needs no configuration class and no annotation of its own — and
still nothing from this project. Its sessions are now written to whatever backend the adapter was
started with.

## Using it from an application

Everything below is a Spring Boot property. The
[session properties](https://docs.spring.io/spring-boot/reference/web/spring-session.html) are
Spring Boot's own and behave as they do against Redis; only their effect on the adapter is
described here.

### Simple mode

The default. Each session is one hash whose expiry Spring Session sets itself. Nothing is announced
and nothing is indexed: a session that has expired is simply no longer there the next time it is
asked for. Use it when the application only needs to read and write sessions.

### Indexed mode

Indexed mode adds what Spring Session cannot do with a hash alone: session events, a lookup by
principal name, and a background job that reaps sessions nobody came back to.

<!-- snippet:app-indexed -->
```properties
spring.session.data.redis.repository-type=indexed
```

Leave `spring.session.data.redis.configure-action` alone. Its default asks the server to turn
keyspace notifications on at start-up, and the adapter answers that the way Redis would, because it
emits those notifications regardless. Setting it to `none` — the equivalent of declaring
`ConfigureRedisAction.NO_OP` — is not needed.

Session events are published as ordinary application events:

<!-- snippet:session-event-listener -->
```java
@Component
public class SessionEventListener {

    private static final Logger logger = LoggerFactory.getLogger(SessionEventListener.class);

    @EventListener
    public void onSessionCreated(SessionCreatedEvent event) {
        logger.info("session {} created", event.getSessionId());
    }

    @EventListener
    public void onSessionExpired(SessionExpiredEvent event) {
        logger.info("session {} expired", event.getSessionId());
    }

    @EventListener
    public void onSessionDeleted(SessionDeletedEvent event) {
        logger.info("session {} deleted", event.getSessionId());
    }

}
```

`SessionExpiredEvent` arrives because the backend reports the key it dropped, not because anything
polled for it. How long that takes is the backend's business: the in-memory one sweeps once a second
by default, and etcd removes the key when its lease runs out, so either way the event follows the
expiry within about a second.

Sessions can be looked up by the user they belong to:

<!-- snippet:find-by-index-name -->
```java
@Service
public class ActiveUserSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public ActiveUserSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public Set<String> sessionIdsOf(String username) {
        return this.sessions.findByPrincipalName(username).keySet();
    }

}
```

The index is filled from the session's principal, which Spring Security sets, or which the
application sets itself under
`FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME`.

### Expiration stores

Indexed mode records when each session is due to expire. By default that record is a set per minute
(`spring:session:expirations:<minute>`), which the cleanup job reads on the minute. An application
that would rather keep one sorted set of every live session declares Spring Session's alternative
store:

<!-- snippet:sorted-set-expiration-config -->
```java
@Configuration
public class SortedSetExpirationConfig {

    @Bean
    public RedisSessionExpirationStore sortedSetRedisSessionExpirationStore(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisOperations = new RedisTemplate<>();
        redisOperations.setKeySerializer(RedisSerializer.string());
        redisOperations.setHashKeySerializer(RedisSerializer.string());
        redisOperations.setConnectionFactory(connectionFactory);
        redisOperations.afterPropertiesSet();
        return new SortedSetRedisSessionExpirationStore(redisOperations,
                RedisIndexedSessionRepository.DEFAULT_NAMESPACE);
    }

}
```

Both stores work against the adapter. Nothing else about it changes: a session's death is still
announced by its shadow key expiring.

### Namespace and database

Both behave as they do against Redis.

<!-- snippet:app-namespace -->
```properties
spring.session.data.redis.namespace=acme:web
spring.data.redis.database=1
```

The database has to exist on the adapter as well, so `redis-adapter.databases` must be at least one
greater than the index the application selects — see the
[configuration reference](#configuration-reference). Nothing else changes: the keyspace
notifications the adapter emits name the database they happened in, so events still arrive.

### Authentication

Give the server a password and clients authenticate exactly as they do against Redis:

<!-- snippet:app-password -->
```properties
spring.data.redis.password=s3cret
```

Until a connection has authenticated the adapter answers everything but `AUTH`, `HELLO` and `QUIT`
with `NOAUTH Authentication required.`. The password crosses the network in clear text, as it does
with Redis, so a protected server on an untrusted network wants TLS as well.

### TLS

When the server serves TLS, the application connects to it the ordinary Spring Boot way, over an
[SSL bundle](https://docs.spring.io/spring-boot/reference/features/ssl.html):

<!-- snippet:app-tls -->
```properties
spring.data.redis.ssl.enabled=true
spring.data.redis.ssl.bundle=adapter
spring.ssl.bundle.pem.adapter.truststore.certificate=file:/etc/app/tls/ca.crt
```

The server side of the same connection is [below](#tls-1).

## Running the server

```bash
java -jar redis-adapter-for-spring-session-server-<version>-exec.jar
```

### Configuration reference

Every setting is an ordinary Spring Boot property, so a command line argument, an
`application.properties` beside the jar, or an environment variable all work.

<!-- properties:redis-adapter -->

| Property | Default | What it does |
| --- | --- | --- |
| `redis-adapter.bind-address` | `0.0.0.0` | The address to listen on. The default accepts on every interface, which is what a server in a container wants. |
| `redis-adapter.port` | `6379` | The port to listen on. `0` binds an ephemeral one. |
| `redis-adapter.backend` | `in-memory` | Which registered backend holds the session data, by the name it answers to. |
| `redis-adapter.password` | none | The password clients must authenticate with. Unset, anything that reaches the port can read and write every session. |
| `redis-adapter.databases` | `1` | How many numbered databases to serve, each an independent keyspace. |
| `redis-adapter.shutdown-timeout` | `10s` | How long a connection still running a command is given before it is interrupted on shutdown. |
| `redis-adapter.ssl.enabled` | unset | Whether to serve TLS. Unset means TLS exactly when a bundle is named; `false` keeps a configured bundle unused. |
| `redis-adapter.ssl.bundle` | none | The `spring.ssl.bundle.*` holding the server's certificate and key. |
| `redis-adapter.ssl.client-auth` | `none` | Whether clients must present a certificate of their own: `none`, `want` or `need`. |
| `redis-adapter.in-memory.sweeper-enabled` | `true` | Whether the in-memory backend sweeps for keys whose time has passed but which nobody has touched. |
| `redis-adapter.in-memory.sweep-interval` | `1s` | How long between sweeps, which is the longest an expired key can sit there unnoticed. |
| `redis-adapter.etcd.endpoints` | `http://localhost:2379` | The etcd cluster's client URLs. Requests go to the one that last worked and move on when a member cannot be reached. |
| `redis-adapter.etcd.key-prefix` | `/redis-adapter/` | Where in etcd's keyspace the sessions live. Each database gets `<key-prefix><database>/` of its own. |
| `redis-adapter.etcd.connect-timeout` | `5s` | How long to wait for a connection to an endpoint. |
| `redis-adapter.etcd.request-timeout` | `5s` | How long to wait for etcd to answer, which bounds how long a Redis command can hang. |
| `redis-adapter.etcd.watch-retry-delay` | `1s` | How long before the watch that delivers session events is opened again after it fails. |
| `redis-adapter.etcd.username` | none | The etcd user, for a cluster with authentication enabled. |
| `redis-adapter.etcd.password` | none | That user's password. |
| `redis-adapter.etcd.ssl-bundle` | none | The `spring.ssl.bundle.*` to reach an `https://` etcd with, and the client certificate for mutual TLS. |

Written as properties, the settings an operator is most likely to change look like this:

<!-- snippet:server-settings -->
```properties
redis-adapter.bind-address=0.0.0.0
redis-adapter.port=6379
redis-adapter.databases=1
redis-adapter.backend=in-memory
redis-adapter.password=s3cret
```

What the backend itself is given is under [Backends](#backends).

The same settings as environment variables, which is how a container platform usually hands them
over:

<!-- snippet:server-env -->
```properties
REDIS_ADAPTER_PORT=16379
REDIS_ADAPTER_PASSWORD=s3cret
REDIS_ADAPTER_DATABASES=16
REDIS_ADAPTER_IN_MEMORY_SWEEP_INTERVAL=5s
REDIS_ADAPTER_ETCD_ENDPOINTS=http://etcd-0:2379,http://etcd-1:2379
```

### TLS

The server's certificate and key come from an SSL bundle as well, and
`redis-adapter.ssl.bundle` names it:

<!-- snippet:server-tls -->
```properties
redis-adapter.ssl.bundle=adapter
redis-adapter.ssl.client-auth=none
spring.ssl.bundle.pem.adapter.keystore.certificate=file:/etc/adapter/tls/tls.crt
spring.ssl.bundle.pem.adapter.keystore.private-key=file:/etc/adapter/tls/tls.key
spring.ssl.bundle.pem.adapter.reload-on-update=true
```

Naming a bundle is enough to serve TLS; clients then connect over `rediss://`. Setting
`redis-adapter.ssl.enabled=true` without a bundle is refused as the properties bind, rather than
leaving a port serving in clear text under a setting that says otherwise. `client-auth=need` turns
the client's certificate into its credential (mutual TLS).

`reload-on-update=true` is the whole of certificate rotation. When the issuer replaces the files on
disk, Spring Boot rebuilds the bundle and the adapter serves the new certificate to every client
that connects afterwards. Nothing restarts, and the connections already open run to their end on
the certificate they were given. A rotation that cannot be read leaves the previous material
serving and is logged at ERROR.

### Health and metrics

The application also serves the Spring Boot actuator over HTTP, on the usual `server.port` (that
port carries nothing but the actuator; sessions are served over RESP on `redis-adapter.port`).

- `GET /actuator/health` reports the adapter as up only while it is accepting connections, with the
  port, the number of databases and how many connections are open. A load balancer can therefore
  take an instance out before its connections are dropped. Whether the *backend* is reachable is
  not asked here: a backend that can be unreachable contributes a health indicator of its own.
- `redis.adapter.connections.active` and `redis.adapter.connections.accepted` are published to
  whatever meter registry is on the classpath.

### Running more than one

The adapter keeps no session state, so replicas behind a load balancer serve the same sessions —
but only as far as the backend does. The in-memory backend does not, since each replica owns its
own map. Scaling out means a backend that is shared, and one that can tell a replica about a key
another replica expired, because that is what an application's `SessionExpiredEvent` is made of.
[etcd](#etcd) does both.

## Backends

Which backend holds the sessions is one property, `redis-adapter.backend`, matched against the name
each backend answers to. Two are bundled, and nothing about the application changes when the answer
changes.

### In-memory

The default, and what the server runs with no configuration at all. Each adapter owns its own map,
so the sessions are gone when it restarts and a second adapter serves different ones. Keys nobody
comes back to are removed by a sweeper.

<!-- snippet:server-in-memory -->
```properties
redis-adapter.in-memory.sweeper-enabled=true
redis-adapter.in-memory.sweep-interval=1s
```

### etcd

Sessions live in an [etcd](https://etcd.io) cluster, so they are shared by every adapter pointed at
it and outlive all of them. This is the backend for running more than one adapter.

<!-- snippet:server-etcd -->
```properties
redis-adapter.backend=etcd
redis-adapter.etcd.endpoints=http://etcd-0:2379,http://etcd-1:2379,http://etcd-2:2379
redis-adapter.etcd.key-prefix=/redis-adapter/
```

Three things are worth knowing:

- **Expiry is etcd's own.** A key with a TTL is attached to an etcd lease, so a session nobody
  comes back to is removed by etcd rather than by anything the adapter polls for. Leases are whole
  seconds and are rounded up, so a key is collected shortly *after* it is due; it is already gone
  as far as every read is concerned, because the exact deadline travels with the value.
- **Events cross the adapters.** Every adapter watches its keyspace, so a session that expires or
  is deleted anywhere is announced to the clients subscribed everywhere. An application connected
  to one adapter therefore hears about a session another adapter removed, which is what
  `SessionExpiredEvent` needs and what the in-memory backend cannot do.
- **The keyspace is yours to choose.** `key-prefix` is where the sessions live, and each database
  gets a keyspace of its own underneath it. Two deployments can share a cluster by taking different
  prefixes; a cluster used for other things is untouched outside them.

It reaches etcd over the HTTP gateway etcd serves on its client port (`--enable-grpc-gateway`, on
by default), which is why no gRPC stack, protobuf or Netty is added to the server. Authentication
is `redis-adapter.etcd.username` / `password`, and an `https://` endpoint is reached through an SSL
bundle named by `redis-adapter.etcd.ssl-bundle` — the same bundles the adapter's own port uses,
including a client certificate for mutual TLS.

#### What it costs

etcd keeps every value in memory, replicates it to every member and commits every write to disk,
so a session write costs more here than it does against Redis and it is worth knowing how much
before planning around it. Measured against a single-member etcd in a container whose commits took
3 ms; `.docs/design/etcd-performance.md` has the full numbers and the harness that takes them, so
you can take your own.

- **A session write costs about ten milliseconds and six etcd raft writes**, a read about half
  a millisecond and one read call. Sessions written per second come out at roughly the cluster's
  raft write rate divided by six; reads barely enter into it.
- **One client connection carries 30 to 50 session writes per second.** Commands on a connection
  are served in order, as Redis serves them, so an application sharing one Lettuce connection
  queues behind itself. More application instances, or more connections, multiply it.
- **Keep session attributes in the tens of kilobytes.** Below that the raft commit dominates and
  the bytes are noise — a 100 KB session costs a few milliseconds more than a 1 KB one. Above
  etcd's `--max-request-bytes` (1.5 MiB by default) the write is refused outright: the client is
  told `ERR value too large for the backend`, which is the adapter saying the session did not
  fit and that repeating the write will not help, and etcd's own reason is in the adapter's log.
  Nothing is written, so the application sees the save fail rather than a session it cannot read
  back. A cluster also has a total size limit (`--quota-backend-bytes`, 2 GiB by default).
- **One key is contended by design**, and that is handled: every session expiring in the same
  minute joins that minute's set, so the adapter applies the writes waiting for one key together,
  in a single etcd transaction, instead of letting them compete. Hundreds of writers at once
  therefore cost *less* per write rather than more (0.01 etcd calls per write at 256 writers,
  against 15 without it). What still grows is the bucket itself: adding to a minute that already
  holds 10,000 sessions costs about five times what an empty one does, however few writers there
  are.

## What is implemented

Only what Spring Session issues, plus the handshake a client needs to get that far.

<!-- commands -->

| Commands | Used for |
| --- | --- |
| `PING`, `HELLO`, `AUTH`, `CLIENT`, `SELECT`, `QUIT`, `COMMAND`, `CONFIG` | The client handshake. `CONFIG GET`/`SET notify-keyspace-events` is answered so that indexed mode's start-up check passes. |
| `HGETALL`, `HSET`, `HMSET`, `HGET` | The session itself, which is one hash per session. |
| `EXISTS`, `DEL`, `UNLINK`, `RENAME`, `TYPE` | Reading, deleting and changing the id of a session. |
| `EXPIRE`, `PEXPIRE`, `EXPIREAT`, `PEXPIREAT`, `PERSIST`, `TTL`, `PTTL` | Session expiry. |
| `APPEND` | The shadow key of indexed mode, an empty string whose expiry announces the session's death. |
| `SADD`, `SREM`, `SMEMBERS` | The principal index and the expiration buckets. |
| `ZADD`, `ZREM`, `ZREVRANGEBYSCORE` | The optional sorted-set expiration store. |
| `PUBLISH`, `SUBSCRIBE`, `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE` | Session events, together with the `__keyevent@<db>__:del` and `__keyevent@<db>__:expired` notifications the adapter emits when a key goes. |

Values are opaque. Only key names and hash-field names are text; everything else is stored and
returned byte for byte, whichever serializer the application uses.

There is no `SCAN`, `KEYS`, `MULTI`/`EXEC`, `EVAL`, `INFO`-driven feature, cluster slot,
replication or persistence. Spring Session's session path uses none of them.
`.docs/design/redis-command-surface.md` has the exact semantics of each command above.

## Writing a backend

A backend implements one interface, `am.ik.redis.adapter.store.KeyValueStore`, and depends on the
core module only. It never sees RESP, connections or Spring.

<!-- methods:KeyValueStore -->

| Method | Contract |
| --- | --- |
| `currentTimeMillis()` | The store's clock. The command layer converts relative TTLs against it, so expiry stays consistent. |
| `get(byte[])` | The typed value, or `null` if the key is absent or has expired. |
| `exists(byte[])` | Whether the key is there, honouring expiry. |
| `append(byte[], byte[])` | Appends to a string, creating it if absent — appending nothing materializes an empty one. |
| `hset(byte[], Map)` | Sets hash fields, creating the hash if absent. |
| `sadd(byte[], List)`, `srem(byte[], List)` | Adds to and removes from a set; an emptied set removes the key. |
| `zadd(byte[], Map)`, `zrem(byte[], List)` | The same for a sorted set, a member that is already there moving to its new score. |
| `delete(byte[])` | Deletes a key, firing `onDeleted` if it was alive. |
| `rename(byte[], byte[])` | Moves a value and its TTL, firing no key event — a rename must not look like a delete. |
| `expireAt(byte[], long)`, `persist(byte[])`, `getExpireAt(byte[])` | The absolute per-key TTL, in epoch milliseconds. |
| `addKeyEventListener(KeyEventListener)` | Registers the listener the adapter turns into keyspace notifications. |
| `close()` | Releases whatever the store holds; must be idempotent. |

Five rules matter more than the signatures:

- **Expiry is the store's job, and it must be announced.** Every operation honours passive
  expiration: touching a key whose deadline has passed removes it, fires
  `KeyEventListener.onExpired` and then treats it as absent. A store should also expire keys nobody
  touches, or a session that is simply abandoned — the common case — is never announced as expired
  and the application never fires `SessionExpiredEvent`.
- **Events are fired at the moment of removal**, synchronously, never dropped. Spring Session's
  `SessionDeletedEvent` and `SessionExpiredEvent` are made of nothing else.
- **Bytes are opaque.** Keys, field names and members are compared by value (`ByteArrayKey` is
  there for that); payload bytes are returned exactly as they arrived and are never parsed.
- **Two failures have names of their own.** `TypeMismatchException` says the key holds another
  kind of value, and `ValueTooLargeException` says the value is bigger than this backend will
  take; the command layer answers them with `WRONGTYPE` and `ERR value too large for the backend`
  respectively. Both tell the client something it can act on, which is why they are not left to
  the generic path — anything else a backend throws becomes `ERR internal error` and is logged
  with its stack trace. A backend with no size limit never throws the second one.
- **A distributed backend has to carry the events across nodes.** If an adapter replica other than
  the one holding a client's subscription expires a key, that expiry still has to reach the
  subscriber. `KeyEventListener` is the seam where a backend plugs in its own watch or notify
  channel. For a single-node backend it is local and trivial.

Implementations must be safe for concurrent use: the server runs one virtual thread per
connection.

The server picks a backend by name. A backend module contributes one bean, a
`KeyValueStoreFactory`, which is asked for a store per database:

<!-- snippet:backend-factory -->
```java
public class MyKeyValueStoreFactory implements KeyValueStoreFactory {

    @Override
    public String name() {
        return "my-backend";
    }

    @Override
    public KeyValueStore create(int databaseIndex) {
        return new MyKeyValueStore(databaseIndex);
    }

}
```

<!-- snippet:backend-registration -->
```java
@Configuration(proxyBeanMethods = false)
public class MyBackendConfiguration {

    @Bean
    public MyKeyValueStoreFactory myKeyValueStoreFactory() {
        return new MyKeyValueStoreFactory();
    }

}
```

Put the module on the server's classpath and select it:

<!-- snippet:backend-selection -->
```properties
redis-adapter.backend=my-backend
```

Two details are easy to miss. Each database is an independent keyspace, so two calls to `create`
must return stores that share no keys — a shared backend separates them by the index it is given.
And every registered factory is created whether or not it is the one selected, so a factory must
hold no resource and open no connection until `create` is called.

`am.ik.redis.adapter.inmemory.InMemoryKeyValueStore` is the reference implementation, and it
depends on the core exactly as an external backend does.
`am.ik.redis.adapter.etcd.EtcdKeyValueStore` is the worked example of the harder half: how a shared
backend keeps read-modify-write atomic across replicas, and how it carries key events between them.

## Example applications

[`examples/session-example-etcd`](examples/session-example-etcd) is a Spring Boot application that
keeps its sessions in etcd through the adapter, generated from start.spring.io and left as ordinary
as possible: the session starter on the class path, a host and a port in `application.properties`,
and one screen that remembers who you are.

```bash
./mvnw install -DskipTests   # publish the adapter jar to the local repository
cd examples/session-example-etcd
./mvnw spring-boot:test-run  # starts etcd, an adapter, and the application on :8080
```

Its end-to-end tests drive a browser against two instances of the application, and a Spring profile
decides only where the sessions go: `./mvnw test` runs them against two adapters sharing one etcd,
and `./mvnw test -Dspring.profiles.active=redis` runs the same assertions against a real Redis.
Redis is the oracle, so the two runs are expected to agree — including that a session created
against one instance is served by the other.
See [its README](examples/session-example-etcd/README.md).

## Limitations and non-goals

- Reactive / WebFlux is out of scope. Only the servlet session repositories are tested against the
  adapter.
- Redis Cluster, Sentinel, replication and persistence are not implemented, and neither is any
  command Spring Session does not use.
- Only as much of RESP3 as Lettuce needs to complete its handshake and run the session commands.
- The default backend is single-node and in-memory: sessions do not survive a restart and are not
  shared between adapter replicas. Sharing them means [etcd](#etcd) or a backend of your own.
- The etcd backend inherits etcd's shape: values are kept in memory and replicated to every member,
  so it suits sessions rather than large payloads, and a cluster has a total size limit. What it
  costs is measured in [What it costs](#what-it-costs) rather than left to be discovered.

## Building from source

Java 25 or later.

```bash
./mvnw clean spring-javaformat:apply test
```

The etcd backend's tests start a real etcd in a container, so a Docker (or compatible) daemon has to
be running for the full build.

The performance harness is not part of that build — it measures rather than asserts, and it takes
minutes. Run it on its own:

```bash
./mvnw test -Pperformance -pl redis-adapter-for-spring-session-server -am
```

It reports what each backend costs, at the SPI and through a real client, and writes its tables to
`target/performance/`. `.docs/design/etcd-performance.md` is one run of it, written up. Keep the
`-am`: the harness lives in the server module but measures the backend modules, and without it
they come from the local repository rather than from your working copy.

The build has four modules:

| Module | What it is |
| --- | --- |
| `redis-adapter-for-spring-session-core` | The `KeyValueStore` SPI and the protocol, command, pub/sub and server layers. Depends on `slf4j-api` and `jspecify` and nothing else, and contains no backend. |
| `redis-adapter-for-spring-session-inmemory` | The bundled in-memory backend. Depends on the core only, exactly as an external backend would. |
| `redis-adapter-for-spring-session-etcd` | The etcd backend. Also depends on the core only: it talks to etcd's HTTP gateway with the JDK's own client, so no gRPC stack is added to the server. Its tests run against a real etcd in a container. |
| `redis-adapter-for-spring-session-server` | The Spring Boot server, and the end-to-end tests that drive it through a real Lettuce client running stock Spring Session. |

Every example in this README is taken from a source file that the server module's tests compile and
run; `ReadmeExamplesTests` fails if the two drift apart.

## Design documents

- `.docs/design/architecture.md` — the design and the reasoning behind it.
- `.docs/design/redis-command-surface.md` — the exact command and keyspace behaviour.
- `.docs/design/etcd-performance.md` — what the etcd backend costs, and how it was measured.
- `.docs/research/` — how Spring Session uses Redis, cited line by line.

## License

Licensed under the Apache License, Version 2.0.
