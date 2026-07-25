# What the etcd backend costs (measured 2026-07-25)

The etcd backend shipped with its correctness proved against a real etcd and its cost not
measured at all. This is the measurement. It is not a benchmark of etcd, and it is not a
promise: it is one machine's numbers, taken by a harness that is in the repository so that
anyone can take their own.

Read `.docs/design/architecture.md` §11 first — the design being measured — and treat every
absolute millisecond here as belonging to the machine named below. What travels between
machines is the *structure*: how many etcd calls an operation makes, how many of them are
raft writes, and what grows with what.

## How to run it

```bash
./mvnw test -Pperformance -pl redis-adapter-for-spring-session-server
```

The harness is excluded from an ordinary build by its `performance` JUnit tag (surefire's
`excludedGroups`, cleared by that profile), because it takes minutes, needs a Docker daemon
and asserts nothing. Three classes:

| Class | What it measures |
|---|---|
| `BackendSpiPerformanceTests` | one `KeyValueStore` call at a time, `EtcdKeyValueStore` against `InMemoryKeyValueStore`. The in-memory column is what separates "etcd is slow" from "the adapter is slow". |
| `EtcdSessionPerformanceTests` | stock Spring Session in indexed mode over a real Lettuce client, sessions in etcd — the only numbers an application actually waits for. |
| `InMemorySessionPerformanceTests` | the same harness, sessions in a map. The baseline. |

Each writes its tables to `target/performance/*.md`, which is where the numbers below were
copied from rather than retyped. Round trips are counted from etcd's own `/metrics`
(`grpc_server_handled_total`, by method) in a pass of their own — one operation between two
scrapes, with everything it needs already written — so nothing sits between the store and
etcd inflating the latency being timed.

## Where these numbers come from

macOS on aarch64 (16 processors), JVM 25, `quay.io/coreos/etcd:v3.7.1` in a single-member
container with default flags and its data directory on the container's own filesystem.
etcd reported its own disk latency over the run as **0.90 ms** average WAL fsync and
**3.07 ms** average backend commit; no etcd write can be faster than that, and a tuned
cluster on NVMe is several times quicker while three members over a real network are
slower.

Absolute milliseconds moved by up to about 3× between runs on this laptop (Docker Desktop's
virtualized disk: the same `PEXPIREAT` case measured 3.7 ms and 10.8 ms on two runs an hour
apart). The call counts did not move at all, and the order of the cases barely moved, which
is why the conclusions below are drawn from those rather than from the milliseconds. Every
number in one table comes from one run, so the comparisons within a table hold.

## What one session costs an application

Stock `RedisIndexedSessionRepository` over Lettuce, one 1 KB attribute, one operation at a
time. "etcd calls" is what that one operation asked etcd, counted from etcd's metrics.

| What the application does | etcd p50 | p95 | p99 | in-memory p50 | etcd calls | of which raft writes |
|---|---|---|---|---|---|---|
| save a new session | 14.6 ms | 29.6 ms | 31.2 ms | 0.44 ms | 16 | 10 |
| save an existing session (a request touching it) | 16.1 ms | 21.2 ms | 27.5 ms | 0.28 ms | 18 | 12 |
| delete a session | 18.6 ms | 21.6 ms | 23.7 ms | 0.52 ms | 23 | 13 |
| change the session id | 10.5 ms | 12.5 ms | 14.0 ms | 0.13 ms | 13 | 8 |
| load a session (`findById`) | 0.48 ms | 0.89 ms | 2.09 ms | 0.06 ms | 1 | 0 |
| find a principal's sessions | 0.57 ms | 1.08 ms | 1.70 ms | 0.10 ms | 2 | 0 |

Reads are cheap and writes are not. A session write costs 30–60× what the in-memory backend
costs, and the reason is entirely in the last two columns.

## Why it costs that: raft writes per command

One `KeyValueStore` call, and what etcd was asked for it. `Range` is a read; `Txn`,
`LeaseGrant`, `LeaseRevoke` and `DeleteRange` are raft writes, each one committed to disk on
every member.

| Operation | etcd calls | p50 (etcd) | p50 (in-memory) |
|---|---|---|---|
| `HGETALL` | `Range` 1 | 0.67 ms | 0.00 ms |
| `EXISTS` | `Range` 1 | 0.34 ms | 0.00 ms |
| `PTTL` | `Range` 1 | 0.26 ms | 0.00 ms |
| `HSET`, new session (4 fields, 1 KB) | `Range` 1, `Txn` 1 | 1.10 ms | 0.00 ms |
| `HSET`, one field of an existing session | `Range` 1, `Txn` 1 | 1.12 ms | 0.00 ms |
| `APPEND`, new shadow key | `Range` 1, `Txn` 1 | 0.95 ms | 0.00 ms |
| `SADD`, into a bucket of 1000 | `Range` 1, `Txn` 1 | 3.40 ms | 0.08 ms |
| `DEL` | `DeleteRange` 1 | 1.18 ms | 0.00 ms |
| `PERSIST` | `Range` 1, `Txn` 1, `LeaseRevoke` 1 | 6.38 ms | 0.00 ms |
| **`PEXPIREAT`, key with no TTL yet** | `Range` 1, `LeaseGrant` 1, `Txn` 1 | — | — |
| **`PEXPIREAT`, key that already has one** | `Range` 1, `LeaseGrant` 1, `Txn` 1, `LeaseRevoke` 1 | 10.8 ms | 0.00 ms |
| `RENAME` | `Range` 2, `Txn` 2, `LeaseGrant` 1, `LeaseRevoke` 1 | 7.99 ms | 0.00 ms |

`PEXPIREAT` is the expensive one — three raft writes, because the key moves to a freshly
granted lease and the old lease is revoked — and Spring Session issues **three of them per
save**: on the session, on the shadow key and on the expirations bucket. That is where 9 of
the 12 raft writes of a session touch come from.

So the arithmetic an operator can size with:

> **sessions written per second ≈ (the cluster's raft writes per second) ÷ 12**

On this container etcd sustained roughly 1200 calls/s, about 750 of them writes, which is
exactly the ~35 session cycles/s below. A cluster that commits 10,000 writes/s would carry
in the region of 800 session writes/s. Reads do not enter into it.

## Concurrency does not help, and the client's one connection is why

A "cycle" is what a request does: create a session, read it back, touch it.

| Connections | etcd p50 | etcd p99 | etcd cycles/s | in-memory cycles/s | etcd calls per cycle |
|---|---|---|---|---|---|
| 1 | 28.3 ms | 33.0 ms | 34.2 | 1302 | 35 |
| 8 | 221.9 ms | 266.3 ms | 35.4 | 5284 | 35 |
| 32 | 960.9 ms | 2441.6 ms | 29.3 | 6289 | 35 |
| 128 | 4669.4 ms | 8610.7 ms | 24.7 | 4429 | 34.9 |

Throughput is flat and latency grows linearly with the number of callers, which is the
signature of a queue. Two things put it there, and neither is a defect:

- **Spring Session shares one Lettuce connection**, and the adapter serves one connection
  with one virtual thread running a strict request/response loop — RESP requires the replies
  in order, and Redis behaves the same way. So the commands of 128 application threads are
  executed one after another. At 0.05 ms a command that is invisible; at 16 ms a session
  save it is the whole story.
- **etcd's write rate is the floor underneath that**: the calls per cycle stayed at 35
  whatever the concurrency, so nothing was wasted on retries here — the requests were simply
  waiting.

What follows for a deployment: more application instances (or a Lettuce connection per
worker) multiply this, one application instance on one connection does not. Planning on
"about 30 session writes per second per connection, on a cluster that commits ~750 writes/s"
is the honest reading.

## The one genuinely contended key, and where it breaks

Every session expiring in the same minute adds itself to `spring:session:expirations:<minute>`.
Written from many virtual threads inside one adapter, five writes each:

| Writers | p50 | p99 | ops/s | etcd calls per write | Failed |
|---|---|---|---|---|---|
| 4 | 1.82 ms | 89.8 ms | 199 | 2.8 | none |
| 16 | 2.35 ms | 497.6 ms | 156 | 4.1 | none |
| 64 | 1.90 ms | 1104.7 ms | 242 | 6.9 | none |
| 256 | 2.99 ms | 1955.0 ms | 247 | 15.0 | **244 of 1280 (19%)** |

The in-memory backend, same case, loses nothing at 256 writers (0.04 ms p50, 25,437 ops/s).

The failures are real and they matter: `EtcdException: ... gave up after 50 attempts because
the key kept changing underneath it`, which the command layer answers as `ERR internal
error`, which Spring Session surfaces as a failed session save. Compare-and-swap on one key
does not degrade gracefully — the work per successful write grows with the number of writers
(2.8 → 15 calls), so past some concurrency the retries stop keeping up and a share of writes
is simply lost. Raising `maxAttempts` would move the cliff, not remove it, and a p99 of two
seconds is already past usable.

**This is a defect, not a limit to document.** It is not reachable through one Lettuce
connection (commands there are serial), but it is reachable by an application using several
connections, and by several adapter replicas sharing a cluster — which is the deployment
this backend exists for. `.todo/020-etcd-contended-key.md` carries it, with what a fix looks
like.

## Session size

etcd carries the whole value on every write: `HSET` of one field rewrites the entire hash,
and `SADD` of one member rewrites the entire set.

| Session size | `HSET` new | `HSET` one field | `HGETALL` |
|---|---|---|---|
| 1 KB | 1.08 ms | 1.33 ms | 0.16 ms |
| 10 KB | 1.29 ms | 1.48 ms | 0.31 ms |
| 100 KB | 2.45 ms | 3.46 ms | 0.66 ms |

| Members already in the expirations bucket | `SADD` one more |
|---|---|
| 1 | 0.99 ms |
| 100 | 1.26 ms |
| 1,000 | 1.76 ms |
| 10,000 | 7.38 ms |

A 100 KB session costs about three times a 1 KB one, which is far less than the size ratio:
below a few tens of kilobytes the raft commit dominates and the bytes are noise. The set is
the one that grows into something: a minute holding 10,000 sessions costs 7 ms per session
added, seven times what an empty minute costs, and that is on top of the contention above.

Where it stops fitting, with etcd's default `--max-request-bytes` (1.5 MiB):

| One session attribute | What happened |
|---|---|
| up to 1500 KB | accepted (27.6 ms at 1500 KB) |
| 1600 KB, 2000 KB | refused: `etcdserver: request is too large` |
| 4000 KB | refused by the gateway: `grpc: received message larger than max (4096420 vs. 2097152)` |

Two things worth knowing about that. The limit applies to the request etcd decodes, not to
the JSON the adapter sends, so the base64 the value travels as (four bytes on the wire per
three of session) costs bandwidth but does not lower the ceiling. And what the application
sees is `ERR internal error`, with etcd's reason in the adapter's log — accurate, since the
write did not happen, but it does not say "too big"; `.todo/021` covers giving it a message
of its own.

## What this says about the two optimizations the design left out

Architecture §11.7 listed two things deliberately not built. The measurement decides them:

- **Lease reuse instead of grant-and-revoke — worth building.** `PEXPIREAT` on a key that
  already has a TTL is three raft writes, and it is issued three times per session save: 9
  of the 12 writes a save costs, and about two thirds of its latency. Spring Session always
  pushes the same TTL out (the session's `maxInactiveInterval` does not change from request
  to request), which is precisely the case a `LeaseKeepAlive` covers: it renews a lease for
  its original TTL without a raft write per renewal. A save would fall from 12 raft writes
  to around 6, and the arithmetic above says that is a factor of two in sessions per second.
  `.todo/019-etcd-fewer-raft-writes.md`.
- **Avoiding the whole-hash rewrite — measured, not worth it.** Rewriting a whole 1 KB hash
  to change one field costs 1.33 ms against 1.08 ms to write it new: the difference is well
  inside the noise of a raft commit, and it only reaches 1 ms extra at 100 KB, a session size
  the README already advises against. Field-granular keys would buy that millisecond and pay
  for it with a range read per `HGETALL`, a multi-key transaction per `HSET` and an expiry
  that has to be attached to every field. Not a trade worth making. The same is **not** true
  of collections: a set's cost does grow into something (7 ms at 10,000 members), so if the
  expirations bucket is ever redesigned it should be for that and for the contention, not for
  hashes — which is what `.todo/020` proposes.

## What an operator can plan with

- A session write costs about **15 ms** and 12 raft writes against a single-member etcd whose
  commits take 3 ms; a read costs **half a millisecond**.
- **One application connection carries about 30 session writes per second**, because the
  commands on a connection are served in order. Scale with instances or connections.
- The cluster's own ceiling is **its raft write rate divided by twelve** in session writes
  per second.
- Keep sessions in the tens of kilobytes. It is not the bytes that hurt below that, but
  nothing above a megabyte will be written at all.
- A minute's expirations bucket holding thousands of sessions is the one part that gets
  slower as a deployment gets busier, and past a few hundred concurrent writers to it, writes
  currently fail (`.todo/020`).
