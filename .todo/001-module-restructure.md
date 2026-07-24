# 001 — Multi-module restructure & build setup

## Context
The repo is a single-module template (`am.ik.template.Library`). The design
(`.docs/design/architecture.md` §6) calls for a dependency-free **core** library plus a
**Spring Boot server** module. This task creates that skeleton so every later task has a
place to put code. No adapter behaviour is implemented here.

## Depends on
Nothing. Do this first.

## Goal
A building, formatting-clean multi-module Maven project with empty-but-wired modules and
the final package base name, targeting Java 25.

## Steps / deliverables
1. Convert the root `pom.xml` to a parent aggregator (`<packaging>pom</packaging>`,
   `<modules>`), keeping the existing groupId `am.ik.redis`, version, licence, scm,
   developer, central-publishing, `spring-javaformat`, and `nullability-maven-plugin`
   config in `<pluginManagement>`/`<dependencyManagement>` so children inherit it.
2. Create module **`redis-adapter-for-spring-session-core`**:
   - Runtime deps: **only** `org.jspecify:jspecify` and `org.slf4j:slf4j-api` (as today).
   - Test deps: JUnit 5 (api+engine), AssertJ. (End-to-end Spring test deps are added in
     task 005, still test-scoped.)
   - Base package `am.ik.redis.adapter` with the sub-packages from architecture.md §3
     (`store`, `protocol`, `command`, `pubsub`, `server`). Add a placeholder class or
     `package-info.java` per package so the nullability plugin and format run.
   - Move/replace the template `am.ik.template.Library` — delete it; there is no reason to
     keep the placeholder.
3. Create module **`redis-adapter-for-spring-session-server`**:
   - Depends on `core` and `spring-boot-starter` (+ `spring-boot-starter-actuator`).
   - Base package `am.ik.redis.adapter.boot` with a minimal `@SpringBootApplication` main
     class that does nothing yet (server wiring is task 008).
   - This module intentionally breaks the "no external deps" rule for the *server only*
     (see architecture.md §6); the core must stay clean.
4. Keep `java.version=25` (already set and confirmed). The stale Java-17 lines in
   `CLAUDE.md` were already corrected to Java 25 (Prerequisites + Code Standards) during
   design. Still to do here: update the `**Package**: TBD` line in `CLAUDE.md` to
   `am.ik.redis.adapter` once the packages exist.
5. Check the release helper scripts (`*-version.sh`, `How-To-Release.md`) still work with
   the parent/child layout; adjust only if they assume a single module. Note any change in
   the task's closing summary.

## Acceptance criteria
- `./mvnw clean spring-javaformat:apply compile` builds parent + both modules.
- `./mvnw spring-javaformat:apply test` is green (a trivial smoke test per module is fine).
- `mvn -q -pl redis-adapter-for-spring-session-core dependency:tree` shows no runtime deps
  beyond jspecify + slf4j-api (+ their nulls). The server module may show Spring Boot.

## Notes / gotchas
- Decide the child artifactIds now and record them; later tasks reference `core` / `server`
  by role, not exact id.
- Do not add Lettuce/Spring Session yet — they arrive as **test** deps in 005.
- Formatting: `spring-javaformat` is enforced at `validate`; run `:apply` before compiling.
