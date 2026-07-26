# TODO overview — Redis Adapter for Spring Session

Each `NNN-*.md` file is a self-contained unit of work that a fresh Claude Code session can
pick up cold. Every task assumes you have first read:

- `.docs/design/architecture.md` — the overall design and module layout.
- `.docs/design/redis-command-surface.md` — the exact command/keyspace behaviour to build.
- The relevant `.docs/research/0*.md` files (cited per task).

Spring Session sources to consult live at `/Users/toshiaki/git/spring-session`
(module `spring-session-data-redis`). Spring Data Redis 4.1.0 sources were extracted during
research; if you need them again, unzip
`~/.m2/repository/org/springframework/data/spring-data-redis/4.1.0/spring-data-redis-4.1.0-sources.jar`.

## Sequence & dependencies

```
001 restructure ─┬─ 002 store SPI + in-memory ─┐
                 ├─ 003 RESP codec ─── 004 server + handshake ─┐
                 │                                             │
                 └────────────────── 005 data cmds + simple E2E ┴─ 006 pubsub/set/keyspace ── 007 indexed E2E
                                                   │                                              │
                                                   │                           013 part 1 (raw) ──┤
                                                   └───────────── 008 Spring Boot server module ──┤
                                                                                                  ├─ 013 part 2 (E2E)
                                                                                                  ├─ 009 ZSet (optional)
                                                                                                  ├─ 010 TLS (SslBundle) ─┬─ 014 native image / container
                                                                                                  │                       └─ 015 cert rotation (optional)
                                                                                                  └─ 011 docs / README (last, after 009–014)
```

- **001** must land first (build structure).
- **002** and **003** are independent and can be done in parallel after 001.
- **012** (split the in-memory backend into its own module) needs 002; do it before 004/005
  so the end-to-end tests are written in the `-inmemory` module from the start.
- **004** needs 003. **005** needs 002 + 003 + 004. **006** needs 005. **007** needs 006.
- **008** needs a working core (after 005; richer after 007). **009** (ZSet) after 007.
- **013** (non-default namespace / database) closes the one gap 007 leaves, since every E2E
  written so far runs on the defaults. It is **split around 008**: its part 1 (a
  raw-protocol test that the keyspace channel carries the database index) needs only 004
  and lands before 008, while its part 2 (the Spring Session E2E) is built on the
  `databases` property 008 introduces rather than on a throwaway test configuration.
- **010** (TLS) needs 004's `ServerSocketFactory` seam + 008's Spring Boot module. **015**
  (certificate rotation without a restart) is the follow-up it deliberately left out; it is
  optional and blocks nothing, 011 included.
- **014** (native image / container) is best done after 010, since certificate material
  needs resource hints in a native binary. It also carries a rule that applies to every
  task after it: Spring evaluates `@Conditional` while the image is built, so a property an
  operator sets at deploy time must never decide which beans exist. 008 already had to
  design for this.
- **011** (docs/README) comes last, after 007–010 and 014, so documented behaviour (incl.
  TLS and however the server is shipped) is proven.
- **016** (etcd backend) came after 011 and needed only the SPI and the backend-selection
  seam 008 introduced. It is the first backend that is *shared*, which is what turns §7 of
  the architecture (horizontal scaling) from a design note into something that works, and
  the first that can be *unreachable* — which is what 017 is for. It had no todo file of its
  own: it was asked for and built in one session, so there is nothing to recover and no
  history row.
- **017** (backend health indicator) closes the gap 016 opened: `README.md` promises that a
  backend which can be unreachable contributes a health indicator, and none does yet.
- **018** (etcd performance) closes the other one: 016's correctness is proved against a real
  etcd and its cost is not measured at all. It blocks nothing, but it decides whether the two
  optimizations §11.7 of the architecture lists are worth their complexity, and it is what
  turns README's qualitative advice about session size into something an operator can plan
  with. Done 2026-07-25; `.docs/design/etcd-performance.md` is the result, and it decided both
  optimizations and turned up three things of its own: **019**, **020** and **021**.
- **019** (fewer raft writes per save) and **020** (a contended key must not fail a save) both
  come out of 018's numbers and are independent of each other; 019 is a throughput improvement
  with a known factor, 020 is a defect. Neither needs anything 017 does. **021** (a write too
  large for the cluster gets its own error) is the smallest of the three and touches the core's
  error mapping rather than the etcd backend, so it is the one that affects every backend.
- **020** was done 2026-07-25, by batching rather than by the key-per-member layout it also
  weighed: the callers of one adapter now queue at a key (`KeyQueues`) and whoever holds it
  applies everything queued in one transaction. At 256 writers to one bucket that is 0.010 etcd
  calls per write instead of 15, and none lost instead of 19%. It left 019 untouched, and it
  leaves the *size* of a bucket untouched, which is the one thing a key per member would still
  address.
- **019** was done 2026-07-25. A `PEXPIREAT` that asks for the TTL the key's lease already
  renews to now renews that lease (`LeaseKeepAlive`, which etcd serves without a raft proposal)
  instead of granting one and revoking the other, so a session save costs **6 raft writes
  instead of 12** — 28.6 ms down to 8.84 ms, and 20.7 request cycles per second up to 51.8.
  The TTL a lease renews to travels in the value, which raised the envelope's format byte to 2
  — nothing has been released, so format 1 is refused rather than still read. The two
  remaining ideas of §11.7 are unaffected.

## Definition of done (every task)

- `./mvnw clean spring-javaformat:apply compile` succeeds for all modules.
- `./mvnw spring-javaformat:apply test` is green.
- New behaviour is covered by a **failing-first** test (unit or auto-runnable E2E) per the
  repo bug-fix / TDD rule.
- Follow the `java-code-standards`, `spring-code-standards`, `java-package-structure`, and
  `java-testing-standards` skills. Comments/Javadoc in English. No emojis in docs.
- On completion run the notify command:
  `osascript -e 'display notification "<body>" with title "<title>"'`.

## Status

| # | Title | State |
|---|---|---|
| 001 | Multi-module restructure & build setup | done |
| 002 | KeyValueStore SPI + in-memory backend | done |
| 003 | RESP protocol codec | done |
| 012 | Split the in-memory backend into its own module | done |
| 004 | Virtual-thread TCP server + handshake commands | done |
| 005 | Data commands + simple-mode end-to-end | done |
| 006 | Pub/Sub + Set commands + keyspace notifications | done |
| 007 | Indexed-mode end-to-end (events + index + cleanup) | done |
| 008 | Spring Boot server module (config, lifecycle, actuator) | done |
| 013 | Non-default namespace and database end-to-end (part 1 before 008, part 2 after) | done |
| 009 | ZSet commands for SortedSetRedisSessionExpirationStore (optional) | done |
| 010 | TLS via Spring Boot SslBundle | done |
| 014 | GraalVM native image and container image | not started |
| 011 | README, docs & tested examples | done (native image / container left to 014) |
| 015 | Reload the server certificate without a restart (optional) | done |
| 016 | etcd backend (shared, so several adapters serve the same sessions) | done |
| 017 | Health indicator for a backend that can be unreachable | not started |
| 018 | Measure what the etcd backend costs | done |
| 019 | Cut the raft writes a session save costs | done |
| 020 | A contended key must not fail a session save | done |
| 021 | A write etcd is too small for deserves its own error | not started |
| 022 | A FoundationDB backend | spiked, ready to build |
