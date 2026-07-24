# 012 — Split the in-memory backend into its own module

## Context
The `KeyValueStore` SPI (task 002) is the single pluggable seam. To prove that seam is real
— usable by a backend that lives entirely outside `core` and depends only on the SPI — the
bundled in-memory reference backend is moved out of `core` into its own module, becoming a
peer of any future external backend rather than a privileged part of `core`. Decision and
rationale are recorded in `.docs/design/architecture.md` §6 (and §3).

Do this **before task 005** (the first end-to-end tests), and ideally before task 004, so
the E2E tests are written in the right module from the start rather than moved later.

## Depends on
002 (the store SPI + `InMemoryKeyValueStore` exist). Independent of 003.

## Goal
A new `redis-adapter-for-spring-session-inmemory` module holding the in-memory backend, with
`core` reduced to the SPI + protocol/command/pubsub/server layers, and `server` depending on
both.

## Steps / deliverables
1. New Maven module `redis-adapter-for-spring-session-inmemory` (add to the parent `pom.xml`
   `<modules>`). Runtime deps: only `slf4j-api` + `jspecify` (same rule as `core`). Depends
   on `core`. Test deps: only JUnit 5 + AssertJ (backend unit tests). The full-stack E2E
   tests do **not** live here — they live in `server` (see step 5).
2. Move `InMemoryKeyValueStore` from `core` to this module under the new package
   `am.ik.redis.adapter.inmemory` (do **not** reuse `am.ik.redis.adapter.store` — a package
   split across two JARs is a modularity hazard). Add a `package-info.java` (`@NullMarked`,
   matching the existing convention). Move `InMemoryKeyValueStoreTest` with it.
3. Keep in `core` under `am.ik.redis.adapter.store`: `KeyValueStore`, `RedisValue` +
   `StringValue`/`HashValue`/`SetValue`, `ByteArrayKey`, `KeyEventListener`,
   `TypeMismatchException`, and `ByteArrayKeyTest`.
4. Point the `server` module at `core` + `inmemory`, wiring `InMemoryKeyValueStore` as the
   default backend. Add the E2E test deps here (test-scoped Lettuce + Spring Session +
   Spring Boot Test).
5. Ensure the reactor stays acyclic (`inmemory → core`, `server → core` + `server →
   inmemory`). The end-to-end compatibility tests (task 005+) live in the **`server`
   module** — it already has `core`, the backend, and Spring Boot, and is the runnable
   application. They must never live in `core`: `core` has no concrete backend, and a
   test-scoped dependency from `core` onto a backend module would create a reactor cycle.

## Acceptance criteria
- `./mvnw clean spring-javaformat:apply compile` and `./mvnw spring-javaformat:apply test`
  are green for all three modules.
- `core` main classpath still has only `slf4j-api` + `jspecify` and contains no concrete
  `KeyValueStore` implementation.
- The package-cycle ArchUnit guard still passes; no split packages across modules.
- `InMemoryKeyValueStore` is resolvable only via the new module and its new package.

## Notes / gotchas
- This is a pure relocation + module wiring change; no behavioural change to the backend.
- Update `CLAUDE.md` (Design Requirements: package + module list) once the module exists.
- Future external backends follow this exact shape (own module, `am.ik.redis.adapter.<name>`
  package, depends on `core` only).
