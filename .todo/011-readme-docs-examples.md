# 011 — README, docs & tested examples

## Context
Document the project for two audiences: **application developers** who point Spring Session
at the adapter, and **backend authors** who implement a new `KeyValueStore`. Per the repo
docs standard, examples must be real and tested.

## Depends on
007 (so documented behaviour is proven). 008 for the "run the server" instructions. 009 if
ZSet support shipped. 010 so TLS / `rediss://` setup can be documented.

## Goal
A `README.md` (and any supporting `docs/`) that explains when to use this library, how an
app connects, how to run the server, and how to add a backend — with code examples that are
covered by tests.

## Key references
- `.docs/design/architecture.md` (source of the narrative — do not contradict it).
- `documentation-standards` skill (simple, honest, no emojis, API-user perspective).

## Content to cover
1. **What it is / when to use it**: run any KVS behind Spring Session while the app talks
   plain Redis; no KVS dependency on the app; stateless, horizontally scalable server on
   virtual threads. Be honest about scope (servlet only; in-memory backend is single-node).
2. **App side (the common case)** — show that the app needs nothing from this project:
   ```java
   @Configuration
   @EnableRedisHttpSession            // or @EnableRedisIndexedHttpSession
   class SessionConfig { }
   ```
   ```properties
   spring.data.redis.host=adapter-host
   spring.data.redis.port=6379
   ```
   Note that indexed mode's session events work out of the box (no
   `ConfigureRedisAction.NO_OP` needed).
3. **Running the server**: `java -jar redis-adapter-for-spring-session-server.jar` with the
   `redis-adapter.*` (or env var) configuration table; the in-memory backend default;
   Docker example if useful. Include TLS: `redis-adapter.ssl.*` referencing a
   `spring.ssl.bundle.*` bundle (task 010), with a `rediss://` Lettuce client example.
4. **Supported Spring Session features**: simple vs indexed, session events,
   `findByIndexName`, expiration/cleanup; and the default Set-based expiration store (plus
   sorted-set if 009 shipped).
5. **Backend authoring guide**: the `KeyValueStore` SPI contract, the passive/active expiry
   + `KeyEventListener` requirement, the opaque-bytes rule, and the cross-node
   pub/sub/keyspace consideration for distributed backends (architecture.md §7). Do not
   name specific external systems.
6. **Limitations / non-goals**: reactive/WebFlux, cluster/sentinel, persistence, and the
   unimplemented Redis commands (redis-command-surface.md §G).

## Acceptance criteria
- Every code/config example in the README is exercised by a test (the E2E harness or a
  dedicated doc-sample test), per the repo rule that README examples must be tested.
- README builds a coherent story consistent with `.docs/design/architecture.md`.
- No emojis; English; concise and honest tone.

## Notes / gotchas
- Update the project `CLAUDE.md` "Implemented Features" / "Package" sections to reflect
  reality once features land (the `TBD`s).
- Keep `.docs/design/*` and `.docs/research/*` as the deep reference; the README links to
  them rather than duplicating detail.
