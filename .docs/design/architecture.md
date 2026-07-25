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
  string/hash/set/zset) with `ByteArrayKey`, `KeyEventListener`,
  `TypeMismatchException`. The in-memory reference implementation does **not** live here; it
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
- **`redis-adapter-for-spring-session-inmemory`** — the bundled in-memory reference backend
  (`am.ik.redis.adapter.inmemory.InMemoryKeyValueStore`: `ConcurrentHashMap` + TTL +
  passive/active expiry). **Depends only on `core`; runtime deps only `slf4j-api` +
  `jspecify`.** It implements the `KeyValueStore` SPI from outside `core`, exactly like a
  future external backend, so the seam is exercised for real rather than trusted. Test deps
  are only JUnit 5 + AssertJ (backend unit tests); the full-stack end-to-end tests live in
  the `server` module (below), keeping this module a light, dependency-minimal backend.
- **`redis-adapter-for-spring-session-server`** — Spring Boot application that depends on
  `core` + `inmemory` and wires the in-memory backend as the default:
  `@ConfigurationProperties` (bind address, port, backend selection, default TTL, optional
  auth, DB count), a `SmartLifecycle` bean that starts/stops `RedisAdapterServer`, actuator
  health/metrics, runnable jar. **This module intentionally depends on Spring Boot** — the
  "no external dependencies" rule applies to the reusable core, not to the runnable server
  (the deliberate trade-off chosen for the server). It also **hosts the end-to-end
  compatibility tests** (test-scoped Lettuce + Spring Session + Spring Boot Test): they boot
  the server backed by the in-memory store and drive it through a real Lettuce client
  running stock Spring Session. E2E belongs here because this module already has every
  dependency (core, the backend, Spring Boot) and is the runnable application; `core` cannot
  host them — it has no concrete backend, and a test dependency from `core` onto a backend
  module would create a Maven reactor cycle.

- **`redis-adapter-for-spring-session-etcd`** — the etcd backend (§11), added 2026-07-25.
  Depends on `core` only, exactly as `inmemory` does, and has the same runtime deps
  (`slf4j-api` + `jspecify`): it speaks etcd's v3 API as JSON over the gRPC gateway with the
  JDK's `HttpClient`, so no gRPC stack, protobuf or Netty reaches the server. Test deps add
  Testcontainers, because the backend is only worth anything if it works against a real
  etcd. The Spring Boot side of it (`EtcdBackendProperties`,
  `EtcdKeyValueStoreFactory`) lives in the `server` module, exactly like the in-memory
  backend's, since `KeyValueStoreFactory` is a Spring concept and a package is never split
  across two modules.

Dependency direction is strictly acyclic: `inmemory → core`, `etcd → core`, and
`server → core` + `server → inmemory` + `server → etcd`. Every KVS backend — the bundled in-memory one and any future external
one — depends on `core` only and implements `KeyValueStore`; the in-memory backend is
deliberately a peer of those future backends rather than a privileged part of `core`.

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
`.docs/design/etcd-performance.md` is that measurement, taken by a harness in the server
module that is excluded from an ordinary build (`./mvnw test -Pperformance -pl
redis-adapter-for-spring-session-server`). Three numbers from it belong in the design itself,
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
