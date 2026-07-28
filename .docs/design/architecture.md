# Architecture — Redis Adapter for Spring Session

Status: design baseline (2026-07-24). This document is the single source of truth that
every task under `.todo/` refers to. Read it first before starting any task.

## 1. Goal

Let a Spring application store its HTTP sessions in an **arbitrary key-value store (KVS)**
while *looking, to the application, exactly like it is talking to Redis*. The application
uses stock Spring Session Data Redis (`@EnableRedisHttpSession` /
`@EnableRedisIndexedHttpSession`) and a stock Redis client (Lettuce, pulled in by
`spring-boot-starter-data-redis`). It only changes the connection target
(`spring.data.redis.host` / `port`) to point at **this adapter** instead of a real Redis
server.

Benefits:

- The application classpath needs **no KVS-specific dependency** and no code from this
  project — only a normal Redis client. Swapping the backend KVS never touches the app.
- The adapter is **stateless**: it holds no session state itself; all state lives in the
  pluggable backend. With a shared external backend you can run many adapter replicas
  behind a load balancer and scale horizontally.
- Only the **minimal subset of Redis commands** that Spring Session actually uses has to
  be implemented — not all of Redis.

This library ships the adapter core, an **in-memory backend** (`ConcurrentHashMap`) and an
**etcd backend** (§11). The in-memory one is the reference and the default; etcd is the
shared one, and it is what makes the horizontal scaling of §7 real. Any further backend is
added the same way, by implementing one SPI from its own module.

## 2. Chosen shape: a standalone RESP server on virtual threads

The adapter is a **standalone process that speaks the Redis wire protocol (RESP)** over
TCP. It is delivered as a **Spring Boot application** (the server module) wrapping a
**dependency-free core**.

Why a network server rather than an in-process `RedisConnectionFactory`:

- Only a network server keeps the KVS dependency off the application classpath (the app
  just points Lettuce at `host:port`).
- "Stateless + easily scalable + virtual threads" only makes sense for a server that
  handles many client connections. The core uses **one virtual thread per TCP connection**
  (`Executors.newVirtualThreadPerTaskExecutor()` + blocking `java.net` sockets), which
  scales to very large connection counts with trivial code and **no external IO library
  (no Netty)** — satisfying the core's "no runtime dependencies" rule.
- Because the app's client is real Lettuce, **Lettuce handles all the intricate Spring
  Data Redis `RedisMessageListenerContainer` / `Subscription` machinery** for us. We only
  implement the well-specified RESP wire behaviour plus keyspace-notification emission.
  (Contrast: an in-process `RedisConnection` would force us to satisfy the subscription
  handshake contract described in `.docs/research/03-*`. The server design avoids all of
  it.)

### How the application connects (target end state)

> Revised (2026-07-25, task 011): a Spring Boot application needs **no configuration class
> and no annotation at all**. `spring-boot-starter-session-data-redis` brings Boot's own
> session auto-configuration, and the mode is a property. The annotations below still work
> and remain covered by the end-to-end suite, but the documented setup is the properties
> one, and `README.md` shows that.

```properties
spring.data.redis.host=<adapter-host>
spring.data.redis.port=6379
spring.session.data.redis.repository-type=indexed   # omit for simple mode
```
Equivalently, without Boot's auto-configuration:
```java
@Configuration
@EnableRedisHttpSession                // or @EnableRedisIndexedHttpSession
class SessionConfig { }
```
The app needs nothing from this project. In indexed mode the default
`ConfigureNotifyKeyspaceEventsAction` will issue `CONFIG GET/SET notify-keyspace-events`
at startup; our server answers those (so the app does **not** need
`spring.session.data.redis.configure-action=none`, nor a `ConfigureRedisAction.NO_OP`
bean, though it may have them).

## 3. Layered design (core module)

All four layers are plain Java 25, no runtime dependencies beyond `slf4j-api` (logging
facade) and `jspecify` (nullness annotations) — the two already on the current POM.

```
                 TCP (RESP2 / RESP3)  ── Lettuce clients (the apps)
                        │
  ┌─────────────────────────────────────────────────────────────┐
  │ server    RedisAdapterServer: ServerSocket accept loop,      │
  │           one virtual thread per ClientConnection            │
  │           (read → dispatch → write; per-conn db & subs state)│
  ├─────────────────────────────────────────────────────────────┤
  │ protocol  RESP reader/writer (RespReader, RespWriter):       │
  │           parse request arrays, encode replies + push frames │
  ├─────────────────────────────────────────────────────────────┤
  │ command   CommandDispatcher + handlers: the minimal Redis    │
  │           command set (see .docs/design/redis-command-surface)│
  │ pubsub    PubSubRegistry (process-wide) + keyspace emitter   │
  ├─────────────────────────────────────────────────────────────┤
  │ store     KeyValueStore SPI  ←── the one pluggable seam      │
  │           InMemoryKeyValueStore (ConcurrentHashMap + TTL     │
  │           + passive/active expiry + KeyEventListener)        │
  └─────────────────────────────────────────────────────────────┘
```

Suggested packages (base `am.ik.redis.adapter`):

- `am.ik.redis.adapter.store` — the **SPI only**: `KeyValueStore`, `RedisValue` (sealed:
  string/hash/set/zset) with `ByteArrayKey`, `KeyEventListener`, `TypeMismatchException`,
  `ValueTooLargeException`. The in-memory reference implementation does **not** live here; it
  is a separate module (`am.ik.redis.adapter.inmemory`, see §6).
- `am.ik.redis.adapter.protocol` — `RespReader`, `RespWriter`, RESP element model.
- `am.ik.redis.adapter.command` — `CommandDispatcher`, `CommandContext`, per-command
  handlers, error formatting (`ERR no such key`, `WRONGTYPE`, …).
- `am.ik.redis.adapter.pubsub` — `PubSubRegistry`, `Subscriber`, glob matcher, keyspace
  notification emitter.
- `am.ik.redis.adapter.server` — `RedisAdapterServer`, `ClientConnection`.

Server (Spring Boot) module base package `am.ik.redis.adapter.boot`.

## 4. The KeyValueStore SPI (the pluggable seam)

The SPI is the **entire contract a future backend must satisfy**. Keep it minimal and
Redis-agnostic. Sketch (final signatures decided in task 002):

- Values are **typed**: an entry is one of `StringValue(byte[])`, `HashValue(Map<field,
  value>)`, `SetValue(Set<member>)`, `ZSetValue(Map<member, score>)`. Fields/members/values are
  raw `byte[]`; the store must key everything by a **value-equal wrapper**, never raw
  `byte[]` (which has identity `equals`).
- Per-key absolute TTL: `expireAt(key, epochMilli)`, `persist(key)`, `getExpireAt(key)`.
- `delete(key) -> boolean existed`, `exists(key)` — both honour **passive expiration**:
  touching a key whose TTL has elapsed evicts it and fires the expiry event.
- `KeyEventListener { onExpired(byte[] key); onDeleted(byte[] key); }` — the store calls
  these; the command/pubsub layer turns them into `__keyevent@<db>__:expired` / `:del`
  notifications. **A removal is never silent** — Spring Session's
  `SessionExpiredEvent`/`SessionDeletedEvent` depend on them. The in-memory backend fires
  them synchronously, inside the removal. A shared backend fires them from wherever it
  learns about the removal — for etcd, its watch, a round trip later (§11.3) — because that
  is the only way a replica hears about a key another replica removed. Either way the
  notification is never dropped, and a client sees it on a different connection from the
  reply anyway.
- The in-memory impl additionally runs a background **active expiry sweeper** on a virtual
  thread so keys that are never accessed still fire `expired` in bounded time.

Values are **opaque** to the adapter: only key names and hash-field names are UTF-8 text;
values/hash-values/set-members are JDK-serialized blobs that must be stored and returned
byte-for-byte and never interpreted. (See `.docs/research/04-*` §2.)

Databases: Redis has numbered DBs. Spring Session uses DB 0 by default and our factory is
not Lettuce/Jedis-detected, so `resolveDatabase()` always yields 0. Model the store as N
independent keyspaces (default is fine to start with DB 0 only) and include the DB index
in keyspace-notification channel names.

## 5. Command surface (summary)

Full detail with exact semantics, key formats, and edge cases:
`.docs/design/redis-command-surface.md`. In short:

- Simple mode (`@EnableRedisHttpSession`): `HGETALL`, `HSET`/`HMSET`, `EXISTS`, `DEL`,
  `RENAME` (must error `ERR no such key` on missing source), `PEXPIRE`, `PEXPIREAT`,
  `PERSIST`, `APPEND`.
- Indexed mode adds: `SADD`, `SREM`, `SMEMBERS`; pub/sub `PUBLISH`, `SUBSCRIBE`,
  `UNSUBSCRIBE`, `PSUBSCRIBE`, `PUNSUBSCRIBE`; keyspace-notification emission on `DEL` and
  TTL expiry; `CONFIG GET`/`CONFIG SET notify-keyspace-events` (tolerant).
- Handshake/session: `PING`, `HELLO` (RESP2/3 negotiation), `AUTH` (validated when a
  password is configured, accepted otherwise — see §8.1), `CLIENT`
  (`SETINFO`/`SETNAME` → OK), `SELECT`, `QUIT`, `COMMAND` (minimal). The exact handshake
  set is pinned empirically against a real Lettuce client (task 004).
- Beyond Spring Session: `SET` (no options) and `GET`, so that a backend can be tried out with a
  `redis-cli` rather than only through an application.
- Opt-in (`SortedSetRedisSessionExpirationStore`): `ZADD`, `ZREM`, `ZREVRANGEBYSCORE`
  (task 009, done). An application that declares that bean gets one sorted set of
  expirations instead of the minute buckets; nothing else about the adapter changes,
  because a session's death is still announced by the shadow key.

## 6. Module layout & dependency rules

Multi-module Maven (parent = current artifact, packaging `pom`):

- **`redis-adapter-for-spring-session-core`** — the SPI plus the protocol / command /
  pubsub / server layers in §3, but **not** a concrete backend. **Runtime deps: only
  `slf4j-api` + `jspecify`.** Test deps: JUnit 5, AssertJ, ArchUnit. No Netty, no Spring on
  the main classpath.
- **`redis-adapter-for-spring-session-inmemory`** — the in-memory reference backend
  (`am.ik.redis.adapter.inmemory.InMemoryKeyValueStore`: `ConcurrentHashMap` + TTL +
  passive/active expiry). **Depends only on `core`; runtime deps only `slf4j-api` +
  `jspecify`.** It implements the `KeyValueStore` SPI from outside `core`, exactly like a
  future external backend, so the seam is exercised for real rather than trusted. Test deps
  are only JUnit 5 + AssertJ (backend unit tests); the full-stack end-to-end tests live in
  the `server` module (below), keeping this module a light, dependency-minimal backend.
- **`redis-adapter-for-spring-session-etcd`** — the etcd backend (§11), added 2026-07-25.
  Depends on `core` only, exactly as `inmemory` does, and has the same runtime deps
  (`slf4j-api` + `jspecify`): it speaks etcd's v3 API as JSON over the gRPC gateway with the
  JDK's `HttpClient`, so no gRPC stack, protobuf or Netty reaches the server. Test deps add
  Testcontainers, because the backend is only worth anything if it works against a real
  etcd.
- **`redis-adapter-for-spring-session-dynamodb`** — the DynamoDB backend (§12), added
  2026-07-26. Depends on `core` and — the first backend to carry a driver — on
  `software.amazon.awssdk:dynamodb` over `url-connection-client`, with the SDK's Netty and
  Apache HTTP clients excluded (§12.7). The store is handed a built `DynamoDbClient` rather
  than building one, so where DynamoDB is and how it is signed for stays the caller's
  business. Test deps add Testcontainers and the Floci emulator, with the honesty caveat of
  §12.6.
- **`redis-adapter-for-spring-session-foundationdb`** — the FoundationDB backend (§13), added
  2026-07-26. Depends on `core` and on `org.foundationdb:fdb-java`, which is one jar with no
  transitive dependencies at all but which needs the native `libfdb_c` beside it (§13.5) —
  there is no HTTP API to reach FoundationDB by, so unlike etcd this is not a choice. The
  store opens and closes its own database from a cluster file. Test deps add Testcontainers,
  because a backend is only worth anything against the real thing, and the fixture that finds
  or fetches `libfdb_c` (§13.7).
- **`redis-adapter-for-spring-session-server`** — everything the Spring Boot server is
  **except** a backend: `RedisAdapterProperties` (bind address, port, optional auth, DB
  count, TLS), `RedisAdapterServerAutoConfiguration` registered through
  `AutoConfiguration.imports`, a `SmartLifecycle` bean that starts/stops
  `RedisAdapterServer`, actuator health/metrics, and the `KeyValueStoreFactory` SPI.
  **Depends on `core` + Spring Boot and on no backend** — the "no external dependencies"
  rule applies to the reusable core, not to the runnable server (the deliberate trade-off
  chosen for the server). It produces no runnable jar. It **hosts the end-to-end
  compatibility tests** (test-scoped Lettuce + Spring Session + Spring Boot Test): they boot
  the server on a test-local backend and drive it through a real Lettuce client running
  stock Spring Session. E2E belongs here because the surface they exercise is this module's
  and the backend under it is a fixture; `core` cannot host them — it has no concrete
  backend, and a test dependency from `core` onto a backend module would create a Maven
  reactor cycle. The test sources are published as a **`test-jar`**, so the harness
  (`AdapterServerTestConfiguration`, `SessionKeys`, `ReadmeSnippets`, `BackendSpiBenchmark`,
  `SessionPerformanceHarness`, `CallCounter`, the TLS material) is what every server module
  is proven with, including one built outside this repository.
- **`redis-adapter-for-spring-session-server-<backend>`** — the runnable server around one
  backend, one module per store: `<Backend>BackendProperties`,
  `<Backend>KeyValueStoreFactory`, `<Backend>BackendConfiguration` and a
  `@SpringBootApplication`, in `am.ik.redis.adapter.boot.<backend>`, producing the `exec`
  jar. `-server-inmemory`, `-server-etcd`, `-server-dynamodb` and `-server-foundationdb` are
  the four here. The
  Spring side of a backend
  lives with the server rather than with the store because `KeyValueStoreFactory` is a
  Spring concept and the store module has no Spring on it.

  This split, made 2026-07-26, is what the server module exists for. The alternative — one
  server aggregating every backend, choosing between them by `redis-adapter.backend` — makes
  supporting a store whose driver cannot be published (Gemfire, say) impossible without
  forking: the aggregate has to name it. With a server per store the aggregation is gone,
  the property is gone with it, and a private module of exactly this shape, depending on the
  released `-server` artifact, is a first-class server. `RedisAdapterServerAutoConfiguration`
  therefore requires **exactly one** `KeyValueStoreFactory` bean and refuses to start on any
  other number: which jar is running *is* the choice, so there is nothing left to decide at
  deploy time and nothing for an ahead-of-time image to settle in advance.

Dependency direction is strictly acyclic and, after the 2026-07-26 split, has no edge from
the server to a backend at all: `inmemory → core`, `etcd → core`, `server → core`, and
`server-<backend> → server` + `server-<backend> → <backend>`. Every KVS backend — the
in-memory one and any external one — depends on `core` only and implements `KeyValueStore`;
the in-memory backend is deliberately a peer of those other backends rather than a
privileged part of `core`, and its server is a peer of theirs rather than a privileged part
of `server`. The only edge from `server` to a backend is `server → inmemory` in **test**
scope, because a server with no store cannot serve anything and its tests need one.

> Decision (2026-07-24): the in-memory backend was moved out of `core` into its own
> `-inmemory` module for the reasons above. Naming: module
> `redis-adapter-for-spring-session-inmemory`, package `am.ik.redis.adapter.inmemory`. The
> SPI types (`KeyValueStore`, `RedisValue` + records, `ByteArrayKey`, `KeyEventListener`,
> `TypeMismatchException`) stay in `core` under `am.ik.redis.adapter.store`. The move has
> been carried out; an ArchUnit guard in `core` keeps a concrete backend from creeping
> back in.

## 7. Statelessness & horizontal scaling (design note, not a task by itself)

- The adapter process keeps no durable state; all state is in the backend. The in-memory
  backend is inherently single-node (each replica has its own map) and is therefore the
  **dev / single-instance / test** backend. Horizontal scaling is a property of *external
  shared* backends added later.
- Cross-replica pub/sub / keyspace notifications: when scaled, an expiry detected by
  replica B must reach a subscriber connected to replica A. That requires the *backend* to
  provide a cross-node watch/notify channel; the `KeyEventListener` seam is exactly where a
  distributed backend plugs that in. For the in-memory backend (single node) it is local
  and trivial. The etcd backend does it for real, from etcd's watch (§11.3).

## 8. Authentication and TLS

Two independent protections, meant to be used together on any untrusted network: `AUTH`
decides *who* may talk to the adapter, TLS decides *who can read* what they say.

### 8.1 Authentication (`AUTH`)

The adapter is open by default — anything that reaches the port can read and write every
session. Giving the server a password makes clients authenticate exactly as they do
against Redis. This lives entirely in the core:

- `am.ik.redis.adapter.command.Authenticator` is the seam: `Authenticator.open()` (the
  default), `Authenticator.password(pw)`, `Authenticator.usernamePassword(user, pw)`, or a
  custom implementation backed by whatever credential store an operator has.
  `RedisAdapterServer.Builder` takes `password(...)` or `authenticator(...)`.
- Credentials are compared in constant time and never logged, not even on failure.
- Authentication state is **per connection**. Until a connection authenticates, only
  `AUTH`, `HELLO` and `QUIT` are accepted; everything else is answered
  `NOAUTH Authentication required.` The check sits in `CommandDispatcher`, so a command is
  protected by *how it is registered* (`register` vs `registerUnauthenticated`) rather than
  by each handler remembering to check — a command added later is protected by default.
- Clients send `AUTH [username] password` or `HELLO <protover> AUTH <username> <password>`;
  a password on its own authenticates the user `default`. Wrong credentials get
  `WRONGPASS ...`, and a `HELLO` without credentials on a protected server gets the Redis
  `NOAUTH HELLO must be called with the client already authenticated ...` reply.
- A Spring application needs only `spring.data.redis.password`; Lettuce then carries the
  credentials in its `HELLO`. The matching server-side property (`redis-adapter.password`)
  is task 008.
- On an **open** server `AUTH` still replies `+OK` rather than the Redis error, so a client
  configured with a password can reach an adapter that needs none.

The password itself crosses the network in clear text, exactly as with Redis, which is why
a protected server outside a trusted network also wants §8.2.

### 8.2 TLS (Spring Boot SslBundle)

TLS is terminated by the adapter server so clients can connect over `rediss://`. The
integration is split to keep the core dependency-free:

- **Core** — `RedisAdapterServer` accepts an injected
  `javax.net.ServerSocketFactory`. The default is
  `ServerSocketFactory.getDefault()` (plain TCP). For TLS, an `SSLServerSocketFactory` is
  passed in. The core knows nothing about Spring or certificate config — it just uses the
  factory it is given, and `accept()`s `SSLSocket`s transparently on the same
  virtual-thread-per-connection model.
- **Server (Spring Boot)** — TLS is configured with **Spring Boot's `SslBundle`**
  abstraction, never by hand-loading keystores. The operator defines a bundle under
  `spring.ssl.bundle.*` (JKS/PEM), and `redis-adapter.ssl.bundle=<name>` selects it. The
  server module resolves the `SslBundle` from `SslBundles` and passes
  `SslBundleServerSocketFactory` to `RedisAdapterServer`. That factory owns the
  `SSLContext` (see §8.3) and applies what an `SSLContext` cannot carry: the bundle's
  `SslOptions` (ciphers, enabled protocols) and `redis-adapter.ssl.client-auth`
  (`none`/`want`/`need`, i.e. mutual TLS), both per socket.

When no bundle is named the server stays plain TCP. `redis-adapter.ssl.enabled` is
deliberately *unset* by default rather than `false`: naming a bundle is enough to serve
TLS, `enabled=false` is a written opt-out that keeps the bundle configured, and
`enabled=true` without a bundle is refused as the properties bind. No spelling of the
settings leaves a port plain because a property was forgotten — a plain port answers every
client, so that failure is the one nobody notices. The decision is made inside the bean
rather than by `@ConditionalOnProperty`, so that an operator can still choose it in an
ahead-of-time compiled image (task 014), where conditions were evaluated as the image was
built.

### 8.3 Certificate rotation without a restart

Certificates expire, so they are replaced on disk while the server runs — that is what
cert-manager, Vault and every other issuer do. A bundle declared
`spring.ssl.bundle.pem.<name>.reload-on-update=true` is watched by Spring Boot, which
rebuilds it and reports the new material through `SslBundles.addBundleUpdateHandler`. The
server module registers a handler there and serves the new certificate to every client
that connects afterwards. Nothing is restarted and no connection is dropped.

What makes that possible is **where the material is read**, not what is rebuilt. An
`SSLServerSocket` keeps the `SSLContext` it was created from for as long as it is bound
and hands it to every connection it accepts, so a second `SSLContext` would mean a second
listening socket and a port that is briefly unbound. Instead the context is created once,
over key and trust managers (`RotatableSslContext`) that forward every call to whichever
material was installed last:

- a **handshake in progress or already finished** has read the material it needs and is
  never asked again, so an established connection runs to its end on the certificate it
  was given;
- the **next client to connect** triggers a fresh call into those managers and is served
  whatever the last rotation installed.

Two rules make it safe to follow files an issuer is writing:

- A rotation is **all or nothing**. Both the key and the trust material are read before
  either is installed, so a bundle that has only half landed on disk never produces a
  mismatched pair.
- A rotation that **cannot be read leaves the previous material serving**, logged at
  ERROR with the bundle name (a successful one is logged at INFO). A port serving a
  certificate that is about to expire is worth more than a port serving nothing, and an
  operator can still fix the files. The failure is genuinely reachable: Spring Boot's PEM
  bundles parse lazily, so an unreadable certificate surfaces inside the update handler
  rather than as the bundle is rebuilt.

Nothing about this reaches the core, and no `redis-adapter.ssl.*` property changes for a
rotation — the adapter follows whatever bundle it was pointed at.

## 9. Explicitly out of scope

- **Reactive / WebFlux** (`@EnableRedisWebSession`, `ReactiveRedisConnectionFactory`) —
  servlet/blocking only for now (see `.docs/research/04-*` §1d).
- Redis Cluster / Sentinel, RDB/AOF persistence, replication, and any command Spring
  Session does not use.
- Full RESP3 feature set — only what Lettuce needs to complete its handshake and the
  session commands.

## 10. Reference material in this repo

- `.docs/design/redis-command-surface.md` — durable, distilled command/keyspace reference.
- `.docs/design/etcd-performance.md` — what the etcd backend costs, and the harness that says so.
- `.docs/research/01-non-indexed-command-mapping.md` — exact `RedisSessionRepository` →
  Redis command mapping.
- `.docs/research/02-indexed-command-mapping.md` — exact `RedisIndexedSessionRepository`
  (+ expiration stores) → Redis command mapping, key formats, edge cases.
- `.docs/research/03-pubsub-keyspace-notifications.md` — pub/sub + keyspace-notification
  semantics. NOTE: written against the *in-process* `RedisConnection` alternative; for the
  RESP-server design only the **semantics** (what to publish, on which channel, with what
  body, and the glob-match/`created:*` routing) apply — the `Subscription`/handshake parts
  are handled by Lettuce and are not our concern.
- `.docs/research/04-configuration-and-serializers.md` — how Spring Session wires beans,
  the serializer matrix (String keys/fields, JDK-opaque values), and the single required
  app bean.

All research files cite `file:line` in `/Users/toshiaki/git/spring-session` (Spring
Session sources) and the extracted Spring Data Redis 4.1.0 sources.

## 11. The etcd backend (2026-07-25)

`redis-adapter-for-spring-session-etcd` keeps the sessions in an etcd cluster. It is the
first *shared* backend, so it is where the promises of §7 are either kept or not.

### 11.1 Transport: the gRPC gateway, not a client library

etcd serves its whole v3 API as JSON over HTTP on the same client port as gRPC
(`--enable-grpc-gateway`, on by default). The backend uses that with the JDK's
`java.net.http.HttpClient`, rather than jetcd.

Why: the six RPCs this backend needs (`kv/range`, `kv/txn`, `kv/deleterange`,
`lease/grant`, `lease/revoke`, `watch`) are a small part of what a client library is for,
while jetcd would add grpc-netty-shaded, protobuf and guava to a server whose core
deliberately implements a RESP server without Netty — and would have to be given
reachability metadata for the native image of task 014. The hard parts of this backend
(what a removal means, what a rename must not announce, retry on a lost compare) are ours
either way. The cost is a JSON reader/writer of our own (`Json`) and base64 on the wire.

`EtcdClient` is the seam: if throughput or DNS discovery ever justifies gRPC, one
package-private class changes.

### 11.2 What a key holds, and expiry

One Redis key is one etcd key under `<key-prefix><database>/`, holding an `Envelope`: a
format byte, a type byte, the absolute deadline, the TTL of the lease enforcing it, then the
value. Two mechanisms carry the
TTL and they are not redundant:

- the **etcd lease** the key is attached to removes it when nobody comes back to it — this
  is what replaces the in-memory backend's sweeper, and it means abandoned sessions are
  collected by etcd itself. Leases are whole seconds, so the lease is always rounded **up**:
  a key is never collected before it is due;
- the **deadline in the value** is exact to the millisecond and is what every read compares
  against, so a key is logically gone the moment it should be. A read that finds an overdue
  key removes it, and that removal is what announces the expiry.

`expireAt` pushes a deadline out by **renewing** the lease the key is already on whenever
that lease renews to the TTL the new deadline needs, and grants a new one only when it does
not. etcd renews a lease on the leader alone — no raft proposal is committed for it — where
granting one and revoking another are two proposals. Spring Session issues three
`PEXPIREAT`s per save and always with the TTL it already used, so this is the difference
between one raft write per deadline and three, and it is most of what a session save costs
(§11.6).

A lease renews to the TTL it was *granted* with, so which TTL that is has to be known — and
the replica pushing the deadline out is not necessarily the one that granted the lease.
It therefore travels **in the key**, beside the deadline, which raised the envelope's format
byte — nothing is released, so the previous format is refused rather than read. The renewal
is sent *before* the value is written, never after: a deadline that landed on a lease which then failed to renew would be
a key collected before it is due, while renewing a lease for a write that does not land
costs nothing at all.

Only an exactly equal TTL renews. A longer lease would cover the deadline too, but it would
leave a dead key on the cluster for as long as *it* has left rather than as long as the key
was due — and being that bound is the whole of the lease's job. When a lease is granted the
old one is revoked after the write lands. Revoking matters: Spring Session sets the expiry
on every request, and a lease left behind each time would pile up in etcd until it aged out.
A lease this backend grants is only ever attached to one key, and is only revoked once that
key has been moved off it, so revoking never takes a key with it.

### 11.3 Key events come from a watch

etcd does not say *why* a key was removed, and the difference between `SessionExpiredEvent`
and `SessionDeletedEvent` is exactly that. So every watch asks for `prev_kv`, and the
deadline in the envelope decides: overdue means `onExpired`, otherwise `onDeleted`.

Events are delivered from the watch rather than from the call that caused the removal, which
is what carries them across replicas — the point of a shared backend. A reconnect resumes
from the revision after the last one seen; a compaction past that point is logged, and the
watch starts again from now.

The store reads etcd's current revision **as it is built** and the watch resumes from there,
because a watch opened with no revision only streams what happens after its request arrives
— everything between the store being built and that moment would go unannounced. That read
is allowed to fail (a backend is created while the application starts whether or not it is
the selected one, so an etcd that is briefly away must not take the server with it); the
watch then starts from wherever etcd is when it answers, and the gap is logged at WARN.

Two removals must announce **nothing**: a `RENAME`'s source (a `del` would be read as the
session having been destroyed) and a set or sorted set that lost its last member. Neither
can be expressed by deleting the key, because a delete is what every replica sees. They are
therefore written as a **tombstone** — an envelope with no value — and then removed; a
watcher that sees a tombstone go stays quiet, and every read treats one as an absent key.
Every tombstone carries a short lease, so one left behind by a process that died mid-rename
disappears on its own.

### 11.4 Atomicity and contention

Every read-modify-write is one etcd transaction guarded by the `mod_revision` the read
returned, retried when the guard fails, so two replicas adding to one set cannot lose an
update. `RENAME` is one transaction too (tombstone the source, write the destination),
followed by removing the tombstone; the SPI already allows it not to be atomic across its
two keys.

That settles the conflicts *between* replicas. It does not survive the conflicts an adapter
has with **itself**. Compare-and-swap on one key does not degrade gracefully: the work per
successful write grows with the number of writers, so past some concurrency the retries stop
keeping up and a share of the writes is simply lost — a *failed* session save, not a slow
one. At 256 callers writing one expirations bucket the measurement found 15 etcd calls per
successful write and 19% of them failing outright. And Spring Session has exactly one
genuinely contended key: every session expiring in the same minute adds itself to that
minute's set, so this was the ordinary case rather than a pathological one. Retrying is what
makes contention *correct*; it is not what makes it scale.

So the callers of one adapter do not compete for a key, they **queue at it** (`KeyQueues`):

- everything one adapter does to a key — a mutation, a deadline, a removal, the source of a
  rename — is done with the key to itself. A `RENAME`'s destination is the exception: Redis
  overwrites it whatever it held, so there is nothing there to lose;
- the mutations that pile up while one of them is in flight are applied **together**: one
  value read once, every queued mutation applied to it in the order its caller arrived, one
  transaction, and each caller handed its own answer. That is what serialized execution would
  have produced, which is what Redis — which serializes these anyway — would have produced,
  so nothing about the SPI's answers, its events or the silence of an emptied set changes.

N concurrent writes to one key therefore cost one etcd write instead of N × however many
attempts each of them needed, and the cost per write *falls* as the contention rises rather
than growing with it (§11.6). Two things a batch has to get right, and both are proved
without etcd in `KeyQueuesTest`, where a batch is held open rather than hoped for: a mutation
that refuses the value it is given fails its own caller and nobody else, and a set that
empties part-way through a batch makes what follows it a *new* key, carrying none of the old
one's deadline.

What is left for compare-and-swap is a conflict with another replica — uncommon, but the one
kind of conflict no amount of queueing can remove. Two things make retrying it work rather
than livelock, both found by the concurrency test rather than by reasoning:

- the transaction that refuses a write **reads the key back in its failure branch**, so a
  retry costs one round trip instead of two and the window it can lose in again is halved;
- retries **back off** by a randomized, growing delay (capped at 50 ms).

### 11.5 How it is proved

Everything runs against a real etcd in a container (`quay.io/coreos/etcd:v3.7.1`, pinned so a
failure is reproducible). Three of these suites exist because reasoning about them was not
enough: each one found something.

| Suite | What only a real etcd (or a real outage) can say |
|---|---|
| `EtcdKeyValueStoreTest` | the SPI contract, lease-driven expiry, the silence of a rename, two stores as two replicas, concurrent writes. Found the retry livelock (§11.4); holds the 256-writer case that compare-and-swap alone lost a fifth of, and that pushing the same TTL out again leaves the key on the lease it is already on with no new lease anywhere in the cluster (§11.2). |
| `EtcdWatchReconnectTest` | a removal **and** an expiry that happen while the watch is down are announced when it comes back. Uses a `TcpProxy` the test can blackhole, because etcd has to stay up to be written to while a store is blind. |
| `EtcdEndpointFailoverTest` | a member that stops serving is passed over; a store built while the whole cluster is away still serves once it is back. |
| `EtcdAuthenticationTest` | a protected cluster, and a token the cluster has forgotten. Found that a **watch** is refused *inside its stream* (HTTP 200, then a cancellation saying the token is invalid), so the token has to be discarded from there or the watch reopens forever with a dead one and the application is never told another session ended. |
| `EtcdBackendEndToEndTests` (server) | stock Spring Session over Lettuce: expiry reaching `SessionExpiredEvent` through etcd's lease and watch, and a session another adapter removed reaching this one's subscriber. |
| `EtcdBackendTlsTests` (server) | `redis-adapter.etcd.ssl-bundle` gets the store onto an encrypted etcd — and without the bundle the same store cannot connect, which is what says the bundle did it. |

One suite deliberately runs *without* etcd: `KeyQueuesTest`, for the queueing of §11.4. What
has to hold there — that a batch is really one batch, that no caller is passed over, that one
caller's failure is not everybody's, that a set emptied part-way through a batch is a new key
afterwards — is about the queueing rather than the store underneath, and against a real etcd
every one of those assertions would be probabilistic. Each case that turns on timing holds a
batch open instead of hoping for one.

The watch-startup gap (§11.3) is the one property with no test of its own: it is a race, so a
test would pass either way. It is closed by construction instead — the revision is read before
the watch thread starts.

### 11.6 What it costs (measured 2026-07-25)

Correctness was proved first and the cost measured afterwards;
`.docs/design/etcd-performance.md` is that measurement, taken by a harness that is excluded
from an ordinary build (`./mvnw test -Pperformance`). The shared cases live in the server
module's `BackendSpiBenchmark` and each server module runs them against its own backend, so
the etcd numbers are read against the in-memory ones. Three numbers from it belong in the
design itself,
because they are the design's own consequences rather than one machine's:

- **A session save is 6 raft writes**, one per key written, because the three `PEXPIREAT`s
  Spring Session issues per save renew the lease each key is already on rather than replacing
  it (§11.2). It was 12 before that, 9 of them the leases; the change measured 28.6 ms per
  save down to 8.84 ms and 20.7 request cycles per second up to 51.8. Session writes per
  second are therefore about the cluster's raft write rate divided by six, and nothing else
  matters as much.
- **Reads are almost free and writes are not**: one `Range` against `Txn`-plus-lease-work, or
  0.2 ms against 9 ms on a single-member container whose commits take 3.7 ms.
- **Contention on one key costs less the more of it there is** — now. It is the one thing the
  measurement found that the tests could not, because every correctness suite wrote from a
  handful of threads: at 256 concurrent writers to one expirations bucket the calls per
  successful write had risen to 15 and 19% of the writes failed outright. Queueing at the key
  and applying the queue as one batch (§11.4) turned that into 0.010 calls per write and
  nothing lost — 247 → 22,290 writes per second. What a batch has to answer is now covered by
  a test, so the direction cannot silently reverse.
- **There is a ceiling on one value, and it has an error of its own.** etcd refuses a request
  over `--max-request-bytes` (1.5 MiB by default) and its gateway refuses one over its own
  message limit (2 MiB) before etcd sees it; either way nothing is written and no retry can
  change that. A refusal whose text is about size is raised as the SPI's
  `ValueTooLargeException` rather than as an `EtcdException`, and the command layer answers it
  `ERR value too large for the backend` instead of `ERR internal error` — the difference
  between an application being told to hold less in the session and being sent looking for a
  bug in the adapter. The in-memory backend has no such limit and grows no fake one.

### 11.7 What is deliberately not there

- **No health indicator yet.** README promises that "a backend that can be unreachable
  contributes a health indicator of its own"; `EtcdKeyValueStore.checkHealth()` is the
  method one would be built on. See `.todo/017-etcd-health-indicator.md`.
- **No field-granular hashes** — *decided, and it should not be built.* Rewriting a whole
  1 KB hash to change one field measured 1.33 ms against 1.08 ms to write it new: inside the
  noise of a raft commit, and only about a millisecond apart at 100 KB. Keys per field would
  buy that and pay a range read per `HGETALL`, a multi-key transaction per `HSET` and an
  expiry attached to every field. A **set** is the collection whose cost really grows (10 ms
  to add to a bucket of 10,000 against 1 ms to an empty one), so that is where a layout change
  — one etcd key per member — would still be worth considering. It is no longer the answer to
  contention, which §11.4 settles; it is the answer to a bucket's *size*.
- **Clocks.** Deadlines are absolute milliseconds on the *adapter's* clock, so replicas
  need their clocks roughly in step, which an etcd cluster needs anyway. Skew shows up as a
  key expiring that much early or late, never as a lost session.

## 12. The DynamoDB backend (2026-07-26)

`redis-adapter-for-spring-session-dynamodb` keeps the sessions in one DynamoDB table. It
is the second shared backend, chosen over FoundationDB and Cassandra on the spike recorded
in `.todo/024-dynamodb-backend.md`, whose two headline findings shape everything here: a
`TransactWriteItems` costs what one write costs, and a removal and its announcement can be
written together, atomically. The target is real DynamoDB; the local test store is an
emulator, and §12.6 is honest about what that does not prove.

### 12.1 The layout: one item per member, a meta item per key

One table serves every database. Items live under a string partition key and sort key;
Redis keys, hash fields and members are raw bytes, so they travel base64url-encoded in the
item keys and are never decoded into anything.

| Item | `pk` | `sk` | Attributes |
|---|---|---|---|
| Meta, one per Redis key | `k/<db>/<b64 key>` | `@` | `t` (string/hash/set/zset), `v` (the string payload), `exp` (deadline, epoch ms; absent = no expiry), `ver` (incarnation counter), `ttl` (the storage backstop, §12.3), `duePk`/`dueAt` (the deadline index, §12.4) |
| Hash field | the meta's `pk` | `f/<b64 field>` | `v` |
| Set member | `k/<db>/<b64 key>/<shard>` | `m/<b64 member>` | — |
| Sorted-set member | `k/<db>/<b64 key>/<shard>` | `z/<b64 member>` | `score` |
| Key event | `e/<db>/<bucket second>` | `<epoch ms>/<uuid>` | `key` (raw bytes), `reason` (`del`/`expired`), `ttl` |
| Sweeper lease | `s/<db>` | `@` | `holder`, `leaseUntil` |

The `pk` carries the database index, so databases are independent keyspaces in one table
and the factory holds nothing until `create` is called. Every attribute is referenced
through `ExpressionAttributeNames`, always — `ttl` and `key` are reserved words, and a
list nobody rechecks is how the next reserved word gets through.

**A collection is one item per member**, never a blob: the 400 KB item ceiling caps a blob
set at roughly 20,000 members, and — the measured half of the reason — per-item members
made `SADD` flat from an empty set to 50,000 members with nothing lost at 256 concurrent
writers, which is the case §11.4 records etcd losing 19% of before batching. Hash fields
sit in the meta item's own partition, so reading a session is **one `Query`**; a session
hash never approaches a partition's write ceiling. Set and sorted-set members are
**sharded across `shards` partitions** by member hash, because Spring Session has one
genuinely hot key — every session expiring in the same minute joins that minute's set —
and 1,000 writes per second per partition is a service quota no capacity setting lifts.
Reads of a set fan out over the shards; the shard count is fixed for the life of a table,
because moving it strands members where the old hash put them.

**Writes are transactions, guarded at the meta item.** A mutation that creates a key puts
the meta conditioned on it not existing; one that grows an existing collection carries a
`ConditionCheck` that the meta is still there, still that type and not past its deadline —
deliberately *not* a version compare, so two replicas adding different members to the same
bucket do not conflict at all, which is what the spike's 256-writer result depends on. The
few operations that genuinely read-modify-write one attribute (`APPEND`) or must not act
on a key that was replaced underneath them (`DEL`) compare and bump `ver`. Inside one
adapter, callers of one key are serialized (`KeyLocks`, the exclusive half of §11.4's
answer — there is nothing to batch here, because members are items); so the counts `SADD`
and `HSET` return are exact within one adapter and approximate across replicas, which is
the trade `.todo/024` §"the count question" prices at 6.5x and this design declines to pay.
A transaction takes at most 100 items and a batch 25, so a wider mutation is split — and a
split write is no longer atomic, which is a stated consequence, not a surprise.

An emptied set removes its key (a separate, `ver`-guarded delete after the members go,
announcing nothing). Across replicas that delete can race a concurrent add and strand the
new member invisibly; within Spring Session the only set that empties is an expirations
bucket whose minute has passed, so the stranded entry is one the cleanup job would have
thrown away. Stray children — that race, or a crash between a meta removal and its
children's — are unreadable (every read starts at the meta) and are purged when the key is
next created.

### 12.2 Removal and its announcement are one transaction

`delete`, passive expiry and the sweeper all remove a key the same way: **one
`TransactWriteItems` deletes the meta (guarded on `ver`) and puts the key-event log entry,
with the reason — `del` or `expired` — written as a field.** The two cannot part company,
so the `del`-versus-`expired` guesswork of §11.3 does not exist here, and neither do
tombstones: a removal that must announce nothing (a rename's source, an emptied set)
simply writes no log entry. Children are deleted after the meta, in batches; they are
already unreadable the moment the meta goes.

`RENAME` moves the children, then moves the meta in one transaction (put destination,
delete source), then deletes the source's children. No log entry anywhere, and a stale
destination is passively expired first so its death is announced, as in §11.3.

### 12.3 Expiry is the adapter's; DynamoDB's TTL is a garbage collector

AWS deletes a TTL-expired item *within 48 hours, best effort*, while the Floci emulator
collects about a second after the deadline — a backend that trusted the emulator would
look correct locally and leak every abandoned session in production. So, exactly as the
deadline-in-the-value of §11.2:

- the **`exp` attribute on the meta item** is the expiry, exact to the millisecond, and
  every read compares against it. A read that finds an overdue key removes it through
  §12.2, which is what announces it;
- the **sweeper** (§12.4) is what announces the keys nobody touches;
- the **`ttl` attribute** is written only so that storage for a key no sweeper ever
  reaches is eventually reclaimed. It is the deadline rounded up plus a five-minute
  margin, so nothing is reclaimed before it is due and no emulator's prompt reaper can
  beat the sweeper to a live announcement. It exists on meta and log items only — a child
  item's `ttl` could not follow the deadline that `PEXPIREAT` moves — and **it must never
  be what fires `onExpired`**; a test holds that line, because the environment the tests
  run in will not.

`PEXPIREAT` is one conditional `UpdateItem` of the meta — no read first, since the new
deadline does not depend on the old — which is the "deadline folded into the write" price
the spike measured, and the reason a session save here is round trips rather than raft
writes.

### 12.4 The sweeper, its lease, and the deadline index

Meta items with a deadline carry `duePk`/`dueAt`, a sparse GSI keyed by
`d/<db>/<key-hash shard>` and the deadline — sharded like the members, and for the same
1,000-writes-per-partition reason, since every save writes it. GSI reads are eventually
consistent, always; the index only *nominates* candidates, and every removal re-reads the
meta strongly consistently and is guarded on it, so a stale index entry costs a wasted
read, never a wrong event.

One replica sweeps per database: a **lease taken with one conditional `PutItem`**
(`s/<db>`, taken when absent or lapsed — the spike's 16-racer result), renewed while the
holder lives, released on close. The holder queries each due shard for overdue metas,
removes each through §12.2 with reason `expired`, and trims the event log behind the
cursor lag. A replica that loses the lease simply polls on; a dead holder's lease lapses
and a live replica takes it.

### 12.5 Key events: a polled log, because Streams cannot be one

Every removal that announces writes its log entry in the removal's own transaction
(§12.2), bucketed by second. Each store polls its database's buckets and fires listeners
from what it reads — its own removals included, so every replica hears every event in the
same order, which is §11.3's watch with the watch replaced by a poll. DynamoDB Streams was
measured and rejected: at most two readers per shard is a hard quota the third adapter
replica breaks, and a correct reader needs the Kinesis Client Library, which is a
dependency stack this backend exists to avoid. The poll is the one place this backend is
worse than etcd, and it has two sharp edges, both from the spike:

- **the cursor lags wall-clock** (`cursor-lag`, default 500 ms) and de-duplicates, because
  an entry stamped behind a cursor that has already passed is never seen — the clock
  hazard `.todo/023` found first. The lag must exceed the fleet's clock skew plus a write's
  latency; events therefore arrive roughly `cursor-lag` plus half a `poll-interval` after
  the removal, and both are properties;
- **an idle poll is billed.** The poll interval is a price, not just a latency knob: one
  replica polling strongly consistently every 100 ms is ~0.9 M reads a day, about $3.30
  per replica-database per month against us-east-1 on-demand while nothing happens at all.
  The reads are strongly consistent, because an eventually consistent read that misses an
  entry the cursor then passes is a lost event, and halving that bill is not worth one.

### 12.6 The store under the tests is a fake, and where it parts from the real thing

`CLAUDE.md` promises a networked store is proven against the real thing; AWS publishes no
DynamoDB you can run, so this backend cannot keep that promise and says so instead. The
ordinary build runs everything against **Floci** (`floci/floci`, pinned, through
`io.floci:testcontainers-floci`), which the spike found faithful to every documented limit
it probed — the 400 KB item, the 100-item transaction, the 25-item batch, the refusal of
two operations on one item, reserved words, all-or-nothing cancellation of a failed
*condition*. One place it parts company was found while building, not spiking: a
transaction holding one **oversized** item fails as a plain refusal but Floci still
applies the other puts, where AWS cancels them all. The store therefore decides the size
failure itself, before anything is sent (`requireFits`, raising the SPI's
`ValueTooLargeException`), so its behaviour is the same over the fake and the real thing
— and the server-side mapping stays as the backstop. Four things Floci cannot prove, each
handled by construction and named here so the gap stays a fact rather than becoming an
assumption: TTL timing (§12.3 never depends on it), TTL stream records
(moot — no Streams), throttling (`ProvisionedThroughputExceededException` never occurs
locally; its retry path is exercised against injected failures), and anything about real
throughput or cost. An **opt-in suite against a real table** (`RealDynamoDbTests`, run by
naming a table and region in system properties, skipped otherwise) exists to cover exactly
those; kumo (`ghcr.io/sivchari/kumo`) is the second-opinion emulator, opt-in the same way,
and never the primary — `.todo/024` records the three checks it is missing.

### 12.7 The SDK, and what a session costs

The one driver dependency any backend here carries: `software.amazon.awssdk:dynamodb`
with `netty-nio-client` and `apache5-client` **excluded** and `url-connection-client` used
instead — 28 jars and 6.9 MB instead of 42 and 13, no Netty, no Jackson, no Guava, over
`HttpURLConnection` in the same spirit as §11.1. The exclusions are not optional. The
store is handed its `DynamoDbClient` and does not close it; every request it sends carries
its own `apiCallTimeout`, so how long a Redis command can hang is the store's choice
rather than an inherited default.

What a session costs is round trips and money, not raft writes. Locally against the
emulator a call is ~2 ms whatever it is; an indexed-mode save is about eight calls (one
`Query` and one transaction for the hash, one conditional update per `PEXPIREAT`, a read
and a transaction for the bucket `SADD`, two for the shadow key), so the calls-per-
operation column of the performance report is the number to watch — it is also the bill.
Two facts belong in `README.md` because only an operator can weigh them: a transactional
write is billed at **2x** a plain one (the price of §12.2's atomicity), and the poll and
sweep intervals are standing charges (§12.5) that scale with replicas × databases, not
with traffic.

## 13. The FoundationDB backend (2026-07-26)

`redis-adapter-for-spring-session-foundationdb` keeps the sessions in a
[FoundationDB](https://www.foundationdb.org/) cluster. It is the third shared backend, built on
the spike recorded in `.todo/022-foundationdb-backend.md`. Its one headline finding shapes
everything below: **FoundationDB has real multi-key ACID transactions**, so a whole session save
is one commit rather than six raft writes (§11.6) or eight billed requests (§12.7) — and the
three places §11 and §12 had to build machinery, this backend gets for free.

The other half of the spike is what FoundationDB does *not* have, and each absence forces a
piece of design: **no TTL** (so expiry is entirely the adapter's, §13.3), **no range watch** (so
key events travel through a versionstamped log, §13.4), and a **100,000-byte value ceiling**,
fifteen times smaller than etcd's — which is what decides the layout, and is therefore the first
thing settled.

### 13.1 The layout: one key per field and per member

**Decided before the store was written, because everything else depends on it.** A value may not
exceed 100,000 bytes (`FDBException 2103`), a key 10,000 (`2102`) and a whole transaction 10 MB
(`2101`) — all three measured, all three non-retryable. One `Envelope` per Redis key, the etcd
layout of §11.2, therefore does not transfer: a Spring Session hash holding a serialized
principal and a few attributes over 100 KB is entirely ordinary, and it would be refused.

That left chunking a blob across keys against one key per hash field and per collection member.
**Per-field, per-member keys**, for four reasons, the first two of which are the ones that
decide it:

- **A range read makes it free.** §11.7 declined this layout for etcd because keys per field
  would "pay a range read per `HGETALL`, a multi-key transaction per `HSET`, and an expiry
  attached to every field". FoundationDB charges for none of the three. A range read *is* how
  FoundationDB reads anything; the multi-key write is one ordinary transaction, not a `Txn`
  built by hand; and the expiry does not live on the fields at all, it lives on the meta key
  (§13.3). §12.1 had already reached the same layout for DynamoDB by a different road.
- **Chunking makes atomicity a problem where there was none.** A chunked blob has to be read,
  reassembled, rewritten whole and torn down when it shrinks, and every one of those is a
  correctness question. Per-member keys have no reassembly: `SADD` writes the member's key and
  touches nothing else.
- **Contention disappears rather than being managed.** FoundationDB conflicts at the key. Two
  replicas adding different members to the same expirations bucket write different keys, read
  different keys, and **do not conflict at all** — so the case §11.4 records etcd losing 19% of
  at 256 writers, and which needed `KeyQueues` to fix, does not arise. The same holds for two
  requests setting different fields of one session hash.
- **The ceiling then binds where Redis's does anyway**: on one attribute, not on the session.

So, under a `Subspace` per database (the configured key prefix, then the database index — which
is what makes the databases independent keyspaces, and what lets one cluster serve several
deployments):

| Key | Tuple | Value |
|---|---|---|
| Meta, one per Redis key | `("m", key)` | `(type, deadline)` — the deadline is `null` for a key that does not expire |
| Hash field | `("d", key, field)` | the field's bytes |
| Set member | `("d", key, member)` | empty |
| Sorted-set member | `("d", key, member)` | the score |
| Deadline index | `("x", deadline, key)` | empty |
| Key-event log | `("e", <versionstamp>)` | `(key, reason, stamp)` |
| Log counter | `("c")` | a little-endian counter, `ADD`-mutated |
| Log trim watermark | `("t")` | the versionstamp trimmed through |
| Sweeper lease | `("s")` | `(holder, leaseUntil)` |

Keys are FoundationDB's own [tuple encoding](https://apple.github.io/foundationdb/data-modeling.html),
which is in `fdb-java` and adds no dependency. It matters for a reason beyond tidiness: a Redis
key and a hash field are both arbitrary bytes, so concatenating them with a separator would be
ambiguous, and tuple encoding is not — while still ordering keys the way a range read needs.
Nothing is base64-encoded on the way, unlike §12.1, because FoundationDB keys are bytes.

**Reading a key is one range read** over `("d", key)`, plus the meta. **Writing is one
transaction**, and there is no version counter, no `ConditionCheck` and no compare-and-swap
anywhere in this backend: a transaction that read the meta conflicts, by itself, with anything
that wrote it, and FoundationDB retries it. That is the second thing this backend gets for free,
and it is why `FoundationDbKeyValueStore` is about half the size of `DynamoDbKeyValueStore`.

### 13.2 Removal and its announcement are one transaction, so there are no tombstones

`delete`, passive expiry, the sweeper and an emptied collection all remove a key the same way and
in **one** transaction: clear the meta, clear the children, clear the deadline-index entry, and —
when the removal is one Redis announces — append the key-event log entry with the reason (`del`
or `expired`) written into it as a field.

So the `del`-versus-`expired` guesswork of §11.3 does not exist here, and neither do
**tombstones**: a removal that must announce nothing (a rename's source, an emptied set) simply
writes no log entry. That is the third thing multi-key ACID gives for nothing.

`RENAME` is one transaction too — the destination's children and meta written, the source's
cleared — which is *more* than the SPI asks for (it explicitly allows a rename not to be atomic
across its two keys). A stale destination is passively expired first, in the same transaction, so
its death is announced before it is overwritten, exactly as in §11.3.

### 13.3 Expiry is entirely the adapter's: a deadline index and an elected sweeper

FoundationDB has no TTL, no lease and nothing resembling one — it is the one facility etcd's
design leans on hardest (§11.2) and the largest piece of new work here. The answer is §12.3's,
with the conditional writes replaced by transactions:

- the **deadline on the meta key** is the expiry, exact to the millisecond, and every read
  compares against it. A read that finds an overdue key removes it through §13.2, which is what
  announces it — on this replica and, through the log, on all of them;
- the **deadline index** (`("x", deadline, key)`) is ordered by deadline, so finding what is due
  is one range read from the start of the subspace to `now`. It is written and moved by the same
  transaction that writes the deadline, so it cannot drift out of step with the meta — unlike
  §12.4's GSI, which is eventually consistent and can only nominate;
- the **sweeper** announces the keys nobody touches. One replica sweeps per database, elected by
  a lease at `("s")`: a transaction takes it when it is absent, already this holder's, or lapsed,
  and renews it while the holder lives. A replica that loses the election keeps reading the log,
  so it still hears what the holder announces; a dead holder's lease lapses and a live replica
  takes it. The election is a plain transaction rather than §12.4's conditional `PutItem`,
  because serializable transactions are what this store is made of.

The sweeper works in **bounded batches** — a capped number of keys per transaction — for the
reason §13.6 gives: five seconds is the whole life of a transaction, and a body that is always
too slow retries for ever.

### 13.4 Key events: one watch on a counter, and a versionstamped log

`watch` takes exactly one key, carries no payload, and there is no range watch — so the etcd
design of §11.3, where the watch itself reports each removal, cannot be built. What the spike
proved works, and what this backend does:

- every removal that announces appends a log entry at `("e", <versionstamp>)`, using
  FoundationDB's `SET_VERSIONSTAMPED_KEY` mutation, so the key is stamped with the **commit
  version** and the log is in commit order by construction;
- the same transaction bumps a counter at `("c")` with the atomic `ADD` mutation — atomic
  because `ADD` is a mutation rather than a read-modify-write, and so **adds no conflict**: the
  counter is a key every replica writes on every removal, and any other way of maintaining it
  would make it the one contended key in the design;
- each store keeps a cursor and reads the log forward from it as a range, firing its listeners —
  **its own removals included**, so every replica hears every event in the same order. It then
  waits on a `watch` of the counter for the next wake. The watch is established *before* the
  drain, or a removal committed between the two would not wake anything.

This is better than §12.5's poll in the way that matters: there is **no cursor lag and no clock
hazard**. Versionstamps are the cluster's own commit order, not a timestamp, so an entry cannot
be written behind a cursor that has already passed it, and the fleet's clock skew does not enter
into event delivery at all. What it costs instead is two things the etcd watch gave for free, and
both are built here:

- **the log has to be trimmed**, or it grows without bound. The sweeper clears entries older than
  `log-retention` and records how far it trimmed at `("t")`;
- **a cursor that fell behind a trim has to notice.** A reader whose cursor is below `("t")` has
  missed entries it can no longer read; it says so in the log and jumps to the watermark. This is
  etcd's compaction problem (§11.3) with the roles reversed — there etcd tells the watch, here
  the watermark is what tells it — and the consequence is the same: what was in between is lost,
  and a session that died during it stays until something touches it. `log-retention` is
  therefore a correctness setting, not a housekeeping one.

A store also starts its cursor at the **end** of the log rather than the beginning, read before
the reader thread starts, so a restarted adapter does not replay every event still in the log.
That is §11.3's watch-startup gap, closed the same way and by the same construction.

### 13.5 The native client, which is the price of admission

There is no avoiding a driver. etcd was reachable over its gRPC gateway with the JDK's
`HttpClient` (§11.1) and DynamoDB over `url-connection-client` (§12.7); FoundationDB has **no
HTTP API at all**. `org.foundationdb:fdb-java` is the only client, and it is a JNI shim over the
native `libfdb_c`.

What it does and does not cost:

- the jar has **no transitive dependencies whatsoever** — one jar, nothing like the
  grpc-netty/protobuf/guava stack §11.1 refused or even the 28 jars of §12.7. It is a dependency
  of *this backend module only*; `core` and `server` are untouched, which is the whole point of
  the module split of §6;
- it needs **`libfdb_c`, which is not in the jar** (the jar carries only the JNI shim, per
  platform). A deployment installs the FoundationDB client package the ordinary way and
  version-matches it to the cluster; the tests, which cannot assume anything is installed, find
  or fetch it themselves (§13.7);
- `FDB.selectAPIVersion` may be called **only once per JVM** and starts one network thread for
  the whole process. `FoundationDbKeyValueStore` therefore selects it through a holder that
  refuses a second, different version with a message that says so, rather than letting the
  driver's own error surface. Opening a database is nearly free (16 handles in 0.3 ms in the
  spike), so each store owns and closes its own — which keeps the lifecycle with
  `KeyValueStores.close()` and the factory holding nothing until `create` is called.

A **native image** is explicitly not a condition of this backend: the decision recorded in
`.todo/022` is that a native image is best effort per backend, and this one would need JNI
configuration plus a 24 MB library on top. `.todo/014` carries the note.

### 13.6 Every transaction is bounded, because the default is to hang

Two failure modes here are unbounded by default, and the spike hit both:

- **a transaction may live five seconds**, after which it fails `1007 transaction_too_old` —
  which is *retryable*, so `Database.run()` retries a body that is always too slow **for ever**.
  The spike hung on exactly this and had to be killed;
- **a read against a cluster that is not there never fails.** It waits, indefinitely, unless the
  transaction has a timeout.

So every transaction this store opens sets a **timeout** and a **retry limit** before it does
anything else. Both are persisted across FoundationDB's own retry reset, which is what makes them
bound `run()` rather than each attempt within it; measured, an unreachable cluster with a
one-second timeout fails in 1001 ms with `1031`. `checkHealth()` is the same bounded read, so the
health indicator task 017 describes has something to be built on from the start.

Error mapping, measured rather than assumed:

| FoundationDB | Mapped to | Why |
|---|---|---|
| `2101` transaction too large, `2102` key too large, `2103` value too large | `ValueTooLargeException` | The application has to store less, and no retry can change that. The command layer answers `ERR value too large for the backend` rather than `ERR internal error`. Sizes are also checked *before* a write is sent, so the message names what did not fit |
| `1007`, `1020` and everything else FoundationDB marks retryable | retried by `run()`, within the timeout and retry limit | ordinary conflict |
| anything else, including `1031` timeout | `FoundationDbException` | unreachable, refused, or out of attempts |

### 13.7 How it is proved

Everything runs against a real FoundationDB in a container (`foundationdb/foundationdb:7.3.63`,
pinned, client `org.foundationdb:fdb-java:7.3.63` — the same minor version, because a mixed pair
is untested here and is `.todo/022`'s named follow-up). Two things about that are not the usual
Testcontainers shape, and both are the store's nature rather than incidental:

- **the ports have to match.** A FoundationDB client asserts that the port it reached is the port
  the server advertises, so Testcontainers' "expose a port, read the random mapped one" makes the
  client print `Assertion pkt.canonicalRemotePort == peerAddress.port failed` and time out. The
  fixture picks a free host port and binds it *to itself* (`FDB_NETWORKING_MODE=host`,
  `FDB_PORT=P`, an exact `PortBinding`), which is parallel-safe because P is chosen at run time;
- **`libfdb_c` is found or fetched by the tests**, per platform, cached outside `target/` and
  `System.load`ed from a static initializer. On Linux the release publishes a bare `.so` with a
  `.sha256` beside it; on macOS it is only inside a `.pkg`, which `xar` and `tar` — both present
  on macOS — unpack. An already-installed `/usr/local/lib/libfdb_c.dylib` is preferred. Loading
  the absolute path before anything touches the `FDB` class is enough on both platforms: the
  dynamic loader then satisfies the JNI shim's own `@rpath` reference from what is already in the
  process, so no test needs an environment variable and surefire needs no configuration.

**"Before anything touches the `FDB` class" is a whole-JVM condition, not a per-fixture one**, and
getting that wrong is the one way this module fails that looks like a platform problem and is not.
Most suites reach FoundationDB through the container fixture, which loads the library on the way;
a test that needs no cluster — an unreachable one, a cluster file that is not there — builds a
store directly and gets there first. It then fails once with `UnsatisfiedLinkError`, and since a
class whose initializer threw stays broken for the life of the JVM, *every later test* fails with
`NoClassDefFoundError`, including the ones that would have loaded it. The whole module therefore
passes or fails on which class the runner happens to start with. So the load is registered as a
JUnit `LauncherSessionListener` through `META-INF/services` rather than left to the fixtures, and
it travels in this module's test-jar so the server module built on the backend is covered by the
same registration.

| Suite | What only a real FoundationDB can say |
|---|---|
| `FoundationDbKeyValueStoreTest` | the SPI contract, the deadline index and passive expiry, the silence of a rename and of an emptied set, two stores as two replicas, concurrent writers to one bucket |
| `FoundationDbLogTest` | the log in commit order exactly once across replicas; a cursor left behind by a trim notices and resyncs rather than replaying or hanging |
| `FoundationDbLimitsTest` | the three ceilings and that each is refused as `ValueTooLargeException` with nothing written; a transaction bounded by its timeout against an unreachable cluster |
| `FoundationDbBackendEndToEndTests` (server) | stock Spring Session over Lettuce in indexed mode: a session left to expire reaching `SessionExpiredEvent` through the sweeper and the log, and a session another adapter removed reaching this one's subscriber |

Beside those there is `examples/session-example-foundationdb` (added 2026-07-26), which is the
same browser-driven example the other shared backends have: two application instances, an adapter
each, one cluster, and the identical assertions run against a real Redis under a profile. Neither
of the two things that make this backend awkward reaches it. **The port identity above is a
constraint on a client that crosses the host**, and the adapters do not — they sit on the
cluster's Docker network and reach the advertised address directly, so the fixture is an ordinary
one and the cluster file is simply read out of the container that wrote it. **And `libfdb_c` is a
property of the adapter's image**, put there by copying it out of the FoundationDB image the
cluster itself runs (`COPY --from`), which is both the shortest statement of what a deployment
does and version-matched by construction. The example application links against nothing, which is
the claim §13.5 makes and the one an operator most needs to believe.

### 13.8 What it costs (measured 2026-07-26)

Correctness was proved first and the cost measured afterwards, by the same harness and the same
shared cases as etcd's and DynamoDB's (`./mvnw test -Pperformance`), so the three can be read side
by side. Each server module writes to its own `target/performance/`. Four numbers from it belong
in the design, because they are the design's own consequences rather than one machine's:

- **One write is one commit, and one read is none.** Measured over a hundred of each against a
  single-member container: `HSET` a new session 1.06, `HSET` one field 1.06, `APPEND` 1.05,
  `SADD` 1.05, `DEL` 1.06, `RENAME` 1.06 — and `HGETALL`, `EXISTS`, `PTTL` at 0.05 or below,
  because a read-only transaction commits for nothing. (The 0.05 excess is the counter's own
  sampling boundary, §13.7.) So the unit to plan in is *commits*, against six raft writes per
  save on etcd (§11.6) and about eight billed requests on DynamoDB (§12.7).
- **The layout shows up as writes, not commits.** The same run: `HSET` of a new four-field
  session is 10 keys written in that one commit and `HSET` of one field is 1; `RENAME` is 12.
  That is what one key per field buys — a session save writes what changed, not the session.
- **Contention does not conflict.** 4, 16, 64 and 256 threads adding to one expirations bucket
  lost nothing and cost **0.15 to 0.20 conflicts per write**, with commits staying at ~1.02 per
  write as the concurrency rose. Every ordinary operation measured 0.000. This is §13.1's claim
  and the whole reason for the per-member layout: the case §11.4 records etcd losing 19% of, and
  which `KeyQueues` had to be built for, does not arise.
- **The standing charge is one watch, not a poll.** An idle replica holds one watch per database
  and wakes only when something is removed. §12.5's polled log is billed per interval; this one
  costs nothing while nothing happens. The sweeper's interval is the only recurring work, and it
  falls on the one elected holder.

Absolute times are one laptop's, but the shape is the design's: a write is about 5 ms against a
read's 1 ms on a single-member memory-engine container, and `SADD` into a bucket of 10,000 costs
what `SADD` into a bucket of 1 costs — where etcd's grows fivefold (§11.7).

One thing the harness could not do straightforwardly, recorded because it looks like a bug
otherwise: **FoundationDB's own counters are a periodic snapshot**, cumulative and eventually
exact but seconds behind. A count taken the moment an operation returns reads the sample from
before it. So every count waits for the snapshot to stop moving, and each operation is counted
over a run of a hundred rather than once — a single operation falls inside one sampling window
and cannot be told from its neighbours. A window that moved on neither side of a run is reported
as such rather than as a row of zeros, since "cost nothing" is the most misleading thing a
counter can say.

### 13.9 What is deliberately not there

- **No `KeyQueues`.** The etcd backend needs it because compare-and-swap on one key does not
  degrade (§11.4); here the per-member layout means the contended case does not conflict, and
  what is left is FoundationDB's own retry, which the spike measured degrading gracefully. Adding
  serialization would buy exact `SADD`/`HSET` counts across replicas — the same trade §12.1
  declines — at the cost of the throughput the layout was chosen for.
- **No multi-version client.** The spike only ever ran 7.3.63 against 7.3.63; whether a client of
  one minor version talks to a cluster of another, and whether configuring the multi-version
  client is worth it, is `.todo/022`'s named follow-up rather than something guessed at here.
- **No `examples/session-example-foundationdb`** — until 2026-07-26, when there was. It was left
  out on the grounds that the port-identity constraint of §13.7 is a test fixture rather than
  something to put in front of a reader as the way to run FoundationDB; that turned out to be a
  constraint on a client crossing the host and not on the example at all, since its adapters are
  on the cluster's own network. §13.7 records what it runs.
