# 008 — Spring Boot server module (config, lifecycle, actuator)

## Context
Turn the core server into a runnable, configurable **Spring Boot application** (the
`redis-adapter-for-spring-session-server` module from task 001). This is the deployable
artifact operators run; the app being session-backed connects to it as if it were Redis.

## Depends on
A working core server (after 005; richer after 007). Can start in parallel with 006/007 as
long as it only wraps what core exposes.

## Goal
`am.ik.redis.adapter.boot`: a Spring Boot app that starts/stops `RedisAdapterServer` via a
`SmartLifecycle` bean, is configured through `@ConfigurationProperties`, exposes actuator
health/metrics, and selects the backend (in-memory now; SPI-ready for future backends).

## Key references
- `.docs/design/architecture.md` §2 (how the app connects), §6 (module rules), §7 (scaling).
- Follow `spring-code-standards` (RestClient/JdbcClient not relevant here, but the
  `@ConfigurationProperties` / `@Bean` conventions are).

## Steps / deliverables
1. `@ConfigurationProperties(prefix = "redis-adapter")` (immutable, constructor-bound):
   - `bindAddress` (default `0.0.0.0`), `port` (default `6379`).
   - `backend` selector (default `in-memory`; enum/string so future backends slot in).
   - `defaultTtl` / session-related knobs only if needed (Spring Session sets TTLs itself,
     so this may be unnecessary — keep minimal).
   - optional `password` (enables `AUTH`), `databases` count (default 1/16). The core side
     already exists: `RedisAdapterServer.Builder.password(...)` /
     `.authenticator(Authenticator)` and `.databases(List<KeyValueStore>)` (one store per
     database index) — so this is property binding, not new behaviour. See
     architecture.md §8.1.
   - active-expiry sweep interval.
   - `ssl` group (enabled/bundle) — **wired in task 010** via Spring Boot `SslBundle`; here
     just leave the property placeholder and keep the default `ServerSocketFactory`.
2. A `@Bean KeyValueStore` chosen from `backend` (in-memory → `InMemoryKeyValueStore`).
3. A `SmartLifecycle` (or `@Bean(initMethod/destroyMethod)`) that constructs and
   `start()`s `RedisAdapterServer` on context start and `close()`s it on stop. Bind the
   configured host/port/store/executor. Use `phase` so it starts after the context is ready.
4. Actuator:
   - A `HealthIndicator` reporting server bound/accepting + backend reachability.
   - Micrometer metrics (connections active/total, commands processed, keyspace events
     emitted) — optional but valued for a scalable server.
5. Runnable packaging: `spring-boot-maven-plugin` repackage; a `README`-worthy
   `java -jar` / config-via-env example (env vars map to `redis-adapter.*`).
6. Confirm the "stateless" story: the app holds no session state; document that horizontal
   scaling requires a shared external backend (in-memory is single-node) — see
   architecture.md §7.

## Acceptance criteria (tests first)
- `@SpringBootTest` boots the app, the server binds an ephemeral port (override via
  properties), and a Lettuce client can `PING` it.
- Changing `redis-adapter.port`/`bindAddress` via properties takes effect.
- Actuator `/actuator/health` reports UP when bound; DOWN when the server failed to bind.
- Graceful shutdown closes the server and frees the port (no `Address already in use` on a
  subsequent boot in the same JVM/test).

## Notes / gotchas
- This module may depend on Spring Boot; **core must not** gain Spring on its main
  classpath (verify with `dependency:tree`).
- Keep backend selection open for future KVS modules: depend on the `KeyValueStore` SPI,
  not a concrete store, in the wiring.
- Do not re-expose Spring Session here — this server has nothing to do with the app's
  Spring Session config; it only speaks RESP.
