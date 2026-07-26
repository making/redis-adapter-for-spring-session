# 014 — GraalVM native image and container image

## Context
The server is deployed as a container image, and the intended end state is a **GraalVM
native image** alongside the JVM one. A native binary starts in milliseconds and holds a
fraction of the resident memory, which is what makes running several adapter replicas
cheap — the point of the stateless design in architecture.md §7.

Ahead-of-time compilation changes one rule this project has already had to design around:
**Spring evaluates `@Conditional` once, while the image is built.** A property an operator
is meant to set at deploy time must therefore not decide which beans exist. Task 008 moved
backend selection off `@ConditionalOnProperty` for exactly this reason
(`KeyValueStoreFactory.name()` is matched against `redis-adapter.backend` when the
application starts). The same care is needed for anything added later.

## Depends on
008 (the Boot server module) — done. Best done **after 010 (TLS)**, since SSL bundles need
resource hints in a native image and are easier to get right once TLS works at all. Task
011 (docs) should document whatever this produces, so land this before finishing 011.

## Goal
`./mvnw -Pnative native:compile` produces a binary that serves the same clients the jar
does, and a published container image for both the JVM and the native build. An operator
can still configure everything through environment variables in either image.

## Key references
- `.docs/design/architecture.md` §2 (standalone process), §7 (statelessness and scaling).
- `am.ik.redis.adapter.boot.KeyValueStoreConfiguration` — the runtime backend selection
  and the comment explaining why it is not a condition.
- Spring Boot AOT: `spring-boot-maven-plugin:process-aot`,
  `org.graalvm.buildtools:native-maven-plugin`.

## Steps / deliverables
1. **Declare the `native` profile by hand.** This project does not use
   `spring-boot-starter-parent` (the parent POM imports `spring-boot-dependencies`
   instead), so the profile that parent normally contributes does not exist here: add
   `native-maven-plugin` plus the `process-aot` / `process-test-aot` executions to the
   server module explicitly, with versions from `spring-boot-dependencies`.
2. **Audit what AOT freezes.** Walk the server module for `@Conditional*` and for anything
   else decided at bean-definition time, and confirm that every `redis-adapter.*` property
   an operator would set in a deployment is still read at runtime.
3. **Check the parts that reflect.** `@ConfigurationProperties` records bind through their
   canonical constructor and Boot generates the hints, but prove it rather than assume it
   (step 5). Tomcat, slf4j/logback and the actuator are all supported in native images;
   Tomcat is the largest single part of the image, and it is there so that health checks
   have somewhere to go.
4. **Container image.** Choose between buildpacks (`spring-boot:build-image`) and a
   Dockerfile, and publish a JVM image and a native one. Note that 008 attaches the
   runnable jar under the `exec` classifier; if the image build fights that, dropping the
   classifier is a one-line change and nothing depends on it.
5. **Prove the image serves.** A smoke check outside the unit suite (it needs Docker or a
   built binary): start it, `PING` over RESP, read `/actuator/health`, and — the assertion
   that catches the AOT trap — set `REDIS_ADAPTER_PORT` and `REDIS_ADAPTER_PASSWORD`
   through the environment and confirm both took effect.
6. **Run one indexed-mode round trip against the binary**, not just `PING`: create a
   session, let it expire, see the event. That is the path with the most moving parts
   (keyspace notifications, virtual threads, the sweeper), and the one where a missing hint
   would show up as silence rather than as an error.

7. **Document it in the README**, which now exists: 011 shipped without a container or native
   section, deliberately, since nothing was published yet. Add one under "Running the server"
   with the image names, the `docker run` invocation and the recorded sizes and startup times.
   Every `java`/`properties` block in the README has to be quoted from a file the server module's
   tests run (`<!-- snippet:name -->` against `tag::name[]`); `ReadmeExamplesTests` fails
   otherwise. A `bash` block is prose and needs none of that.

## Acceptance criteria
- The native binary starts and answers both RESP and `/actuator/health`.
- `redis-adapter.*` set through environment variables changes the native binary's
  behaviour — port, password and backend name at least.
- An indexed-mode session round trip (created / expired event) works against the binary.
- Image sizes and startup times are recorded, so 011 can quote them honestly.

## Notes / gotchas
- The AOT rule above is the whole reason this task is worth writing down. Everything else
  is mechanics.
- Task 010 must not put the TLS wiring behind `@ConditionalOnProperty`; decide inside the
  bean, from the property, so that a native image can still be told to serve TLS. Keystore
  and PEM material also needs resource hints to be readable from the binary.
- Task 015 (certificate rotation) rides on Spring Boot's own bundle watching — a
  `WatchService` on a daemon thread, no reflection of ours — and the adapter side is a
  plain `Consumer<SslBundle>`. Confirm rather than assume that a rotation still reaches
  the binary: `RedisAdapterServerCertificateRotationTests` is the check, and if it cannot
  run against a native image it has to be excluded knowingly rather than quietly dropped.
- Virtual threads are supported on GraalVM for JDK 21 and later; the server uses one per
  connection plus a platform thread for the accept loop.
- The `KeyValueStore` SPI reflects on nothing today. A future backend that ships a driver
  may need hints of its own — that is that backend module's business, not the core's.
- **A native image is best effort, per backend** (decided 2026-07-26). The JVM container
  image is the baseline every backend has to reach; a native image is a bonus for whichever
  ones it happens to work for. A backend nobody has managed to build one for is still a
  supported backend, and this task is not a gate on adding one.
- **The FoundationDB backend (server-foundationdb, built 2026-07-26) is the one that makes
  the rule above bite, and it has a JVM-image requirement no other backend has.** It is
  reached through JNI and nothing else — FoundationDB serves no HTTP API — so:
  - **the JVM image has to carry `libfdb_c`**, version-matched to the cluster (23.9 MB on
    Linux; the release publishes a bare `libfdb_c.<arch>.so` with a `.sha256` beside it, so
    the Dockerfile is a download and a copy, not a package install). Without it the server
    starts and then fails every operation with a link error. This is a condition of that
    backend's image, not a nicety, and it is the only backend here with a prerequisite
    outside the jar;
  - `--enable-native-access=ALL-UNNAMED` belongs on the JVM image's command line. The tests
    already pass it (both FoundationDB modules' surefire configuration); without it the JDK
    warns on every run and will refuse outright in a later release;
  - a **native** image would need JNI configuration for `org.foundationdb:fdb-java` on top
    of that library. It is explicitly not a condition of the backend — `.docs/design/
    architecture.md` §13.5 records the decision — so if it turns out not to be worth it, say
    so and move on rather than holding the backend back.
- **The DynamoDB backend (server-dynamodb, built 2026-07-26) needs reachability metadata
  for the AWS SDK** — the SDK reflects over its service model, and Spring Cloud AWS's
  auto-configuration adds its own share. Both publish GraalVM hints (the SDK through the
  reachability-metadata repository, Spring Cloud AWS through its native support), so this
  is expected to be wiring rather than research; it runs over `url-connection-client`, so
  no Netty reaches the image. Best effort per the decision above — the JVM image is the
  baseline.
