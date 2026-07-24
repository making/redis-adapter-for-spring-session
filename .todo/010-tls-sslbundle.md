# 010 — TLS via Spring Boot SslBundle

## Context
Let clients connect to the adapter over TLS (`rediss://`). TLS must be configured through
**Spring Boot's `SslBundle`** abstraction — not by hand-loading keystores — so operators
define certificates the standard Spring Boot way (`spring.ssl.bundle.*`). The core stays
Spring-free: it only receives a `ServerSocketFactory` (architecture.md §8).

## Depends on
004 (the `RedisAdapterServer` `ServerSocketFactory` seam) and 008 (the Spring Boot server
module + `@ConfigurationProperties`).

## Goal
A TLS-capable server: when a bundle is configured, `RedisAdapterServer` binds an
`SSLServerSocket` built from a Spring Boot `SslBundle`; a real Lettuce client with SSL
enabled completes the handshake and runs session commands. Plain TCP remains the default.

## Key references
- `.docs/design/architecture.md` §8 (TLS design + core/server split).
- Spring Boot SSL: `org.springframework.boot.ssl.SslBundle` / `SslBundles`,
  `spring.ssl.bundle.jks.*` / `spring.ssl.bundle.pem.*` properties,
  `SslBundle.createSslContext()`.

## Steps / deliverables
1. Confirm the core seam from 004: `RedisAdapterServer` uses the injected
   `ServerSocketFactory` and `accept()`s work for `SSLSocket` unchanged. If task 004 landed
   without it, add it here (still no Spring in core).
2. Extend `@ConfigurationProperties(prefix = "redis-adapter")` (task 008) with an `ssl`
   group: `enabled` (default false) and `bundle` (the `spring.ssl.bundle.*` name).
   Optionally `clientAuth` (NONE/WANT/NEED) for mutual TLS.
3. In the server module wiring:
   - Inject `SslBundles`. When `redis-adapter.ssl.enabled` is true, resolve
     `SslBundle bundle = sslBundles.getBundle(props.getSsl().getBundle())`, then
     `SSLContext ctx = bundle.createSslContext()`, then
     `ServerSocketFactory f = ctx.getServerSocketFactory()`, and pass `f` to
     `RedisAdapterServer`. Apply `clientAuth` by configuring the created `SSLServerSocket`
     (`setNeedClientAuth`/`setWantClientAuth`) — do this via a thin wrapper
     `ServerSocketFactory` in the server module, or a post-accept hook, keeping core clean.
   - When disabled/absent, keep `ServerSocketFactory.getDefault()`.
4. Do not implement Redis `AUTH`-over-TLS specifics here beyond what task 004 already does;
   TLS is transport-level.

## Acceptance criteria (tests first)
- `@SpringBootTest` with a test `spring.ssl.bundle.pem.*` (self-signed test cert under
  `src/test/resources`) and `redis-adapter.ssl.enabled=true` boots the server on TLS.
- A Lettuce client configured with SSL (`RedisURI` `useSsl(true)`, trusting the test cert)
  completes `HELLO`/`PING` and a session round-trip over TLS.
- With SSL disabled, the server is plain TCP and a plain Lettuce client works (regression).
- (If clientAuth implemented) a client without a cert is rejected under `NEED`.

## Notes / gotchas
- `SslBundle.createSslContext()` gives an `SSLContext`; its `getServerSocketFactory()` is
  the injection point — no manual `KeyManagerFactory`/`TrustManagerFactory` code.
- **Decide TLS inside the bean, not with `@ConditionalOnProperty`.** A native image is
  planned (task 014) and Spring evaluates conditions once, while the image is built, so a
  condition would make `redis-adapter.ssl.enabled` unchangeable in a deployed image. Task
  008 moved backend selection off conditions for the same reason. Certificate material also
  needs resource hints to be readable from a native binary.
- The server currently refuses to start when `redis-adapter.ssl` is set at all, so that
  nobody is served plain TCP while believing otherwise. That check
  (`RedisAdapterServerConfiguration`) is what this task replaces.
- Keep certificate material out of the repo except a clearly-labelled self-signed **test**
  cert. Never commit real keys.
- Bundle hot-reload (`SslBundles.addBundleUpdateHandler`) is a nice-to-have; note it as a
  follow-up rather than blocking this task.
- The core module must gain **no** Spring dependency — all `SslBundle` code lives in the
  server module.
