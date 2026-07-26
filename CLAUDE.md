# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

**Build Commands:**

```bash
./mvnw clean spring-javaformat:apply compile                    # Compile application
./mvnw spring-javaformat:apply test                             # Run all tests
./mvnw test -Pperformance                                       # Measure the backends
```

The last one is the performance harness. It is kept out of an ordinary build by the
`performance` JUnit tag (surefire's `excludedGroups`, cleared by that profile) because it takes
minutes and asserts nothing; it reports, and `.docs/design/etcd-performance.md` is one run of it
written up. It runs over the whole reactor because each server module measures its own backend:
the etcd numbers only mean anything beside the in-memory ones, which are the same cases from
`BackendSpiBenchmark` with the network taken out. Each module writes to its own
`target/performance/`.

```bash
./mvnw install -DskipTests                              # Publish the adapter jar locally
cd examples/session-example-etcd
./mvnw test                                             # End-to-end, over the adapter
./mvnw test -Dspring.profiles.active=redis              # The same tests, over a real Redis
./mvnw spring-boot:test-run                             # The example, on :8080, with its containers
```

Each directory under `examples/` is a project of its own rather than a module, so the reactor
never builds one: it depends on the adapter's installed jar the way any application would. That is
also why the `install` is not optional — without it the tests run against whatever was last
installed, the same trap the `-am` above guards. They drive a browser (Playwright) against two
instances of the example, and a **Spring** profile decides only where the sessions go: the
adapter's container by default, a Redis one under `redis`. The switch is a `@Profile` on the
container beans in `TestcontainersConfiguration`, which is also what `spring-boot:test-run` uses,
so there is one wiring and not two. Redis is the oracle, so a disagreement between the two runs is
the adapter's fault. Docker is required, and the example's own build is the one that runs them — a
change to the adapter is not covered by the reactor's tests alone.

An example is named `session-example-<backend>`, so a second backend sorts next to the first.

## Design Requirements
- **Package**: `am.ik.redis.adapter` - Main package (core module); the in-memory backend module uses `am.ik.redis.adapter.inmemory`, the etcd backend module `am.ik.redis.adapter.etcd`, the common Spring Boot server module `am.ik.redis.adapter.boot`, and each backend's server module `am.ik.redis.adapter.boot.<backend>`. A package is never split across two modules.
- **Modules** - a backend is two modules, the store and the server built around it:
  - `redis-adapter-for-spring-session-core` - dependency-free core: the `KeyValueStore` SPI plus the protocol, command, pubsub and server layers. It never contains a concrete `KeyValueStore` implementation.
  - `redis-adapter-for-spring-session-inmemory` - the in-memory reference backend. Depends on `core` only, exactly like any external backend.
  - `redis-adapter-for-spring-session-etcd` - the etcd backend, the shared one. Depends on `core` only and has the same runtime dependencies: it speaks etcd's v3 API as JSON over the gRPC gateway with the JDK's `HttpClient`, so no gRPC stack reaches the server. Its tests need a Docker daemon (Testcontainers).
  - `redis-adapter-for-spring-session-server` - everything the Spring Boot server is *except* a backend: `RedisAdapterProperties`, `RedisAdapterServerAutoConfiguration` (registered through `AutoConfiguration.imports`), the lifecycle, health, metrics and TLS, plus the `KeyValueStoreFactory` SPI. Depends on `core` only. It contains no runnable application, produces no `exec` jar, and must never gain a dependency on a backend outside test scope. Its tests run on a local `TestBackendConfiguration`, and it publishes them as a `test-jar`: `AdapterServerTestConfiguration`, `SessionKeys`, `ReadmeSnippets`, `BackendSpiBenchmark`, `SessionPerformanceHarness`, `CallCounter` and the TLS material, which is the harness every server module - including one built outside this repository - is proven with.
  - `redis-adapter-for-spring-session-server-<backend>` - the runnable server around one backend: `<Backend>BackendProperties`, `<Backend>KeyValueStoreFactory`, `<Backend>BackendConfiguration` and a `@SpringBootApplication`, and the `exec` jar. `-server-inmemory` and `-server-etcd` are the two here. This is the seam the whole split exists for: a store that cannot be published (Gemfire, say) gets a module of exactly this shape in a private repository, depending on the released `-server` artifact and nothing else.

There is no `redis-adapter.backend` property and there must not be one: which jar is running *is*
the choice. `RedisAdapterServerAutoConfiguration` refuses to start on any number of
`KeyValueStoreFactory` beans other than one.

## Adding a new KVS

A backend is two modules and no change to any existing one. `-inmemory` / `-server-inmemory` is
the smallest complete pair to copy; `-etcd` / `-server-etcd` is the one to copy when the store is
remote. Nothing below touches `core` or `server`.

**1. The store: `redis-adapter-for-spring-session-<kvs>`, package `am.ik.redis.adapter.<kvs>`.**
Depends on `core` only — a compile dependency on `server`, on Spring, or on another backend is the
one mistake that cannot be undone later. Implement `KeyValueStore`; `README.md` §"Writing a
backend" lists the five rules that matter more than the signatures (expiry is the store's job and
must be announced, events fire synchronously at removal, bytes are opaque, `TypeMismatchException`
/ `ValueTooLargeException` are the two named failures, a distributed store carries key events
across nodes). Implementations must be safe for concurrent use: one virtual thread per connection.
A store that talks over a network gets Testcontainers in test scope and is proven against the real
thing, not a fake.

**2. The server: `redis-adapter-for-spring-session-server-<kvs>`, package
`am.ik.redis.adapter.boot.<kvs>`.** Copy `redis-adapter-for-spring-session-server-inmemory/pom.xml`
and change the artifact id, the backend dependency and `<mainClass>`. Four classes, no more:

- `<Kvs>BackendProperties` - a `@ConfigurationProperties(prefix = "redis-adapter.<kvs>")` record,
  validating in its compact constructor and naming the property in every message.
- `<Kvs>KeyValueStoreFactory implements KeyValueStoreFactory` - `name()` returns the backend's
  name (a `public static final String NAME` beside it), `create(int databaseIndex)` returns one
  store per database. **It must hold no resource and open no connection until `create` is
  called**, and each database must be an independent keyspace.
- `<Kvs>BackendConfiguration` - `@Configuration(proxyBeanMethods = false)` +
  `@EnableConfigurationProperties(<Kvs>BackendProperties.class)` + the one factory `@Bean`.
- `<Kvs>RedisAdapterServerApplication` - `@SpringBootApplication` in the same package, so the
  component scan finds the configuration. Everything else arrives from `server`'s
  auto-configuration.

Add both modules to the root `pom.xml` `<modules>`, store before server.

**3. Its tests.** Add `redis-adapter-for-spring-session-server` `<type>test-jar</type>` in test
scope and a `src/test/resources/application.properties` with `redis-adapter.bind-address=127.0.0.1`
and `redis-adapter.port=0`. Then:

- `<Kvs>KeyValueStoreFactoryTests` - the name, one keyspace per database, the property validation.
- the compatibility run - `@Import({ AdapterServerTestConfiguration.class,
  <Kvs>BackendConfiguration.class })` on the test's `SessionApplication`, which drives stock Spring
  Session through a real Lettuce client against this backend. `EtcdBackendEndToEndTests` is the
  model, and indexed mode is the mode worth running: it is the one that can tell a store from
  Redis, because a session's death arrives as a keyspace notification.
- `Readme<Kvs>ExamplesTests` - the property table and the settings example (below).
- `<Kvs>RedisAdapterServerApplicationTests` if the backend can be reached from a test cheaply.

**4. `README.md`.** A `### <kvs>` section under "Backends" holding a
`<!-- properties:redis-adapter.<kvs> -->` table and a `<!-- snippet:server-<kvs> -->` example
quoted from `src/test/resources/readme/server-<kvs>.properties`. Then two registrations, or the
build fails and tells you which: the snippet in `ReadmeExamplesTests.EXAMPLES` (through
`backend("server-<kvs>", ...)`, which reads the sibling module by path), and the table marker in
`ReadmeExamplesTests.PROPERTY_TABLES` against the module that checks it. Add the module rows to the
table in "Building from source" too.

**5. The performance harness**, tagged `@Tag("performance")`. `<Kvs>SpiPerformanceTests` runs
`BackendSpiBenchmark` against the store — the same cases every backend is measured with, which is
what makes the numbers comparable against the in-memory baseline — and adds whatever only this
store has. `<Kvs>SessionPerformanceTests` runs `SessionPerformanceHarness` through a real client.
Both take a `CallCounter`: `CallCounter.NONE` for a store that makes no calls worth counting, or an
implementation of your own (`EtcdCallCounter`) so the report says how many round trips one Spring
Session operation costs.

**6. `examples/session-example-<kvs>`**, if the store can run in a container, plus the module rows
in `.docs/design/architecture.md` §6 and in the module list above.

**A store that cannot be published** — a closed-source or licence-restricted one — is step 2 alone,
in a repository of its own, depending on the released `redis-adapter-for-spring-session-server`
artifact and its `test-jar`. Nothing here is forked, patched or rebuilt for it, and that is the
reason the split exists: keep it possible.

## Implemented Features

`README.md` is the user-facing description; this is only what a change to the code has to keep true.

- RESP2/RESP3 server on virtual threads (one per connection), plus the command set Spring Session
  needs in both simple and indexed mode, pub/sub, and `__keyevent@<db>__:del` / `:expired`
  notifications. `.docs/design/redis-command-surface.md` is the exact contract.
- `AUTH` (per connection, checked in `CommandDispatcher` by how a command is registered) and TLS
  from a Spring Boot `SslBundle`, including certificate rotation without a restart.
- The Spring Boot server module: `redis-adapter.*` properties, lifecycle, actuator health and
  metrics, auto-configured for whichever server module depends on it.
- Two servers: in-memory (single-node) and etcd (shared, so several adapters serve the same
  sessions and a key one of them expires is announced to the clients of all of them).
  `.docs/design/architecture.md` §11 is the etcd design, including why the events come from a watch
  and what a tombstone is for, and §11.6 what it costs. A session write is twelve etcd raft writes,
  so anything added to the write path is measured in those, not in lines of code.

Two rules constrain anything added here:

- **A property an operator sets at deploy time must never decide which beans exist.** Spring
  evaluates `@Conditional` while an ahead-of-time image is built, so the decision has to be made
  inside the bean, from the property (`RedisAdapterServerAutoConfiguration`).
- **Every example in `README.md` is quoted from a file that is compiled and run.** The examples live
  under `src/test/java/com/example` and `src/test/resources/readme`, marked with `tag::name[]`; the
  README marks the same name with `<!-- snippet:name -->`. `ReadmeExamplesTests` in the server
  module checks every one of them, reading a backend's own files by path so that the check does not
  need a dependency pointing the wrong way, and holds the README's command and SPI tables to what
  the code declares. A backend's property table is checked by that backend's server module
  (`Readme<Backend>ExamplesTests`), and `everyPropertyTableIsCheckedBySomeModule` is what stops a
  new table from being added with nobody checking it. Change the example, not the README.

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
