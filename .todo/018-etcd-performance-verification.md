# 018 — Measure what the etcd backend costs

Read first: `.docs/design/architecture.md` §11 (the etcd backend), especially §11.2 (leases),
§11.4 (contention) and §11.6 (what was deliberately left out). `README.md`'s "etcd" section is
what an operator has been told so far.

## Why

Task 016 shipped the etcd backend with its correctness proved against a real etcd, and its
cost **not measured at all**. Everything said about performance so far is a guess:

- a session save is several round trips (`HSET` → read + guarded write, `PEXPIREAT` → read +
  lease grant + guarded write + lease revoke), each of them a raft operation with an fsync;
- `HSET` rewrites the **whole hash**, so a session's cost grows with its attributes;
- values travel as base64 in JSON, so bytes on the wire are a third larger than the payload;
- one key is genuinely contended: every session expiring in the same minute adds itself to
  that minute's set (`spring:session:expirations:<minute>`). §11.4 made retrying survive it;
  nobody has measured what it survives *at*.

None of that is a reason to change the design yet. It is a reason to know the numbers before
someone runs this in front of real traffic — and before deciding whether the two optimizations
§11.6 lists (lease reuse via keepalive, avoiding the whole-hash rewrite) are worth their
complexity.

## What to measure

Two levels, because they answer different questions:

1. **The SPI, directly** (`EtcdKeyValueStore` vs `InMemoryKeyValueStore`, same harness): what
   one etcd round trip costs and how many each operation makes. The in-memory backend is the
   baseline that separates "etcd is slow" from "the adapter is slow".
2. **End to end** (stock Spring Session over Lettuce, as `EtcdBackendEndToEndTests` boots it):
   sessions per second and the latency an application actually sees, which is the only number
   an operator can plan with.

Per case, report p50 / p95 / p99 and the round-trip count, for:

- **save a new session** (the full indexed-mode write path), and **save an existing one** (the
  common case: attributes unchanged, expiry pushed out on every request);
- **load** (`HGETALL`), **delete**, **change the id** (rename);
- **the contended bucket**: N concurrent sessions expiring in the same minute. Find where
  retries stop keeping up — how many attempts are used, and whether
  `EtcdException("...kept changing underneath it")` ever surfaces at a realistic rate;
- **session size**: attributes at 1 KB / 10 KB / 100 KB, up to where etcd's request limit
  (`--max-request-bytes`, 1.5 MB by default) starts refusing writes. Note what the client sees
  when it does — today it is `ERR internal error` with etcd's reason in the log, and that may
  deserve a better message.

## How

- Keep it **out of `mvn test`**: a normal build must stay fast and quiet. Tag the harness
  (`@Tag("performance")`) and exclude that tag in surefire by default, or make it a `main`
  class the developer runs. Say in `README.md`'s build section how to run it.
- One etcd in a container, as the other suites use, and say so in the results: the numbers are
  bounded by that container's fsync latency, so they are a floor rather than a promise. Record
  the machine, the etcd version and whether the data directory was on tmpfs.
- Drive concurrency with virtual threads, one per simulated session, since that is what the
  adapter itself does.
- Write the results to `.docs/design/etcd-performance.md`: the numbers, the harness, and the
  conclusions. Numbers with no method beside them cannot be re-run and are worth nothing a
  release later.

## Done when

- The harness runs on demand, is not part of the ordinary build, and reports the cases above.
- `.docs/design/etcd-performance.md` holds the numbers and how they were taken.
- §11.6 of the architecture is revisited with evidence: either "lease reuse / avoiding the
  whole-hash rewrite is worth it, here is what it buys" or "measured, not worth it".
- `README.md`'s etcd section says something an operator can plan with, in place of today's
  qualitative "keep session attributes small".
- If a measurement turns up a defect (a retry storm, a size limit reported as an internal
  error), it is fixed under a failing test first, as the repo rule requires.
