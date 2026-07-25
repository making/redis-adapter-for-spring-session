# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
./mvnw test -Pperformance -pl redis-adapter-for-spring-session-server   # Measure the backends
```

The last one is the performance harness. It is kept out of an ordinary build by the
`performance` JUnit tag (surefire's `excludedGroups`, cleared by that profile) because it takes
minutes and asserts nothing; it reports, and `.docs/design/etcd-performance.md` is one run of it
written up.

## Design Requirements
- **Package**: `am.ik.redis.adapter` - Main package (core module); the in-memory backend module uses `am.ik.redis.adapter.inmemory`, the etcd backend module `am.ik.redis.adapter.etcd`, and the Spring Boot server module `am.ik.redis.adapter.boot`. A package is never split across two modules.
- **Modules**:
  - `redis-adapter-for-spring-session-core` - dependency-free core: the `KeyValueStore` SPI plus the protocol, command, pubsub and server layers. It never contains a concrete `KeyValueStore` implementation.
  - `redis-adapter-for-spring-session-inmemory` - the bundled in-memory reference backend. Depends on `core` only, exactly like any future external backend.
  - `redis-adapter-for-spring-session-etcd` - the etcd backend, the shared one. Depends on `core` only and has the same runtime dependencies: it speaks etcd's v3 API as JSON over the gRPC gateway with the JDK's `HttpClient`, so no gRPC stack reaches the server. Its tests need a Docker daemon (Testcontainers).
  - `redis-adapter-for-spring-session-server` - Spring Boot server. Depends on `core` + `inmemory` + `etcd`, holds the Spring side of both backends (`*KeyValueStoreFactory`, `*BackendProperties`), and hosts the end-to-end compatibility tests.

## Implemented Features

`README.md` is the user-facing description; this is only what a change to the code has to keep true.

- RESP2/RESP3 server on virtual threads (one per connection), plus the command set Spring Session
  needs in both simple and indexed mode, pub/sub, and `__keyevent@<db>__:del` / `:expired`
  notifications. `.docs/design/redis-command-surface.md` is the exact contract.
- `AUTH` (per connection, checked in `CommandDispatcher` by how a command is registered) and TLS
  from a Spring Boot `SslBundle`, including certificate rotation without a restart.
- The Spring Boot server module: `redis-adapter.*` properties, lifecycle, actuator health and
  metrics, and backend selection by name at startup.
- Two backends: in-memory (default, single-node) and etcd (shared, so several adapters serve the
  same sessions and a key one of them expires is announced to the clients of all of them).
  `.docs/design/architecture.md` §11 is the etcd design, including why the events come from a watch
  and what a tombstone is for, and §11.6 what it costs. A session write is twelve etcd raft writes,
  so anything added to the write path is measured in those, not in lines of code.

Two rules constrain anything added here:

- **A property an operator sets at deploy time must never decide which beans exist.** Spring
  evaluates `@Conditional` while an ahead-of-time image is built, so the decision has to be made
  inside the bean, from the property (`KeyValueStoreConfiguration`, `RedisAdapterServerConfiguration`).
- **Every example in `README.md` is quoted from a file that is compiled and run.** The examples live
  under `src/test/java/com/example` and `src/test/resources/readme` in the server module, marked with
  `tag::name[]`; the README marks the same name with `<!-- snippet:name -->`. `ReadmeExamplesTests`
  fails if the two drift apart, and it also holds the README's property, command and SPI tables to
  what the code declares. Change the example, not the README.

## Development Requirements

### Prerequisites

- Java 25+

### Code Standards

- No external dependencies except for testing libraries
- Use builder pattern if the number of arguments is more than two
- Write javadoc and comments in English
- Spring Java Format enforced via Maven plugin
- All code must pass formatting validation before commit
- Target Java 25 (matches `<java.version>` in the POM); use of Java 21+ APIs such as virtual threads is expected
- Use modern Java technics as much as possible like Java Records, Pattern Matching, Text Block etc ...
- Be sure to avoid circular references between classes and packages.

### Documentation

- Specify when this library should be used
- The explanations are written from the perspective of the API user, with plenty of code examples to make usage easy to understand.
- No need for excessive advertising
- There is no need to use emojis or flashy expressions, just write simply and honestly.

### Testing Strategy

- JUnit 5 with AssertJ
- All tests must pass before completing tasks
- Code examples in the README must be tested to ensure they work.

### After Task completion

- Ensure all code is formatted using `./mvnw spring-javaformat:apply`
- Run full test suite with `./mvnw test`
- For every task, notify that the task is complete and ready for review by the following command:

```
osascript -e 'display notification "<Message Body>" with title "<Message Title>"’
```
