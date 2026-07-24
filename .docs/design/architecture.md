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

This library ships the adapter core plus an **in-memory backend** (`ConcurrentHashMap`).
Other backends are added later by implementing one SPI; they are out of scope here and are
never named in this repository.

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

```java
@Configuration
@EnableRedisHttpSession                // or @EnableRedisIndexedHttpSession
class SessionConfig { }
```
```properties
spring.data.redis.host=<adapter-host>
spring.data.redis.port=6379
```
The app needs nothing from this project. For `@EnableRedisIndexedHttpSession` the default
`ConfigureNotifyKeyspaceEventsAction` will issue `CONFIG GET/SET notify-keyspace-events`
at startup; our server answers those (so the app does **not** need to declare
`ConfigureRedisAction.NO_OP`, though it may).

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
  string/hash/set, zset later) with `ByteArrayKey`, `KeyEventListener`,
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
  value>)`, `SetValue(Set<member>)` (and `ZSetValue` later). Fields/members/values are
  raw `byte[]`; the store must key everything by a **value-equal wrapper**, never raw
  `byte[]` (which has identity `equals`).
- Per-key absolute TTL: `expireAt(key, epochMilli)`, `persist(key)`, `getExpireAt(key)`.
- `delete(key) -> boolean existed`, `exists(key)` — both honour **passive expiration**:
  touching a key whose TTL has elapsed evicts it and fires the expiry event.
- `KeyEventListener { onExpired(byte[] key); onDeleted(byte[] key); }` — the store calls
  these; the command/pubsub layer turns them into `__keyevent@<db>__:expired` / `:del`
  notifications. **Expiry/delete notifications are emitted synchronously at the moment of
  removal** — Spring Session's `SessionExpiredEvent`/`SessionDeletedEvent` depend on them.
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
- Optional (`SortedSetRedisSessionExpirationStore`): `ZADD`, `ZREM`, `ZREVRANGEBYSCORE`
  (task 009).

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

Dependency direction is strictly acyclic: `inmemory → core`, and `server → core` +
`server → inmemory`. Every KVS backend — the bundled in-memory one and any future external
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
  and trivial. This is documented for backend authors (task 011) and is not needed for the
  in-memory deliverable.

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
  server module resolves the `SslBundle` from `SslBundles`, calls
  `sslBundle.createSslContext()`, and injects
  `sslContext.getServerSocketFactory()` into `RedisAdapterServer`. Bundle reload/rotation
  (`SslBundles.addBundleUpdateHandler`) may be honoured later.

This is detailed in task 010. When no bundle is configured the server stays plain TCP.

## 9. Explicitly out of scope

- **Reactive / WebFlux** (`@EnableRedisWebSession`, `ReactiveRedisConnectionFactory`) —
  servlet/blocking only for now (see `.docs/research/04-*` §1d).
- Redis Cluster / Sentinel, RDB/AOF persistence, replication, and any command Spring
  Session does not use.
- Full RESP3 feature set — only what Lettuce needs to complete its handshake and the
  session commands.

## 10. Reference material in this repo

- `.docs/design/redis-command-surface.md` — durable, distilled command/keyspace reference.
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
