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
./mvnw test -Pperformance -pl redis-adapter-for-spring-session-server -am
```

The harness is excluded from an ordinary build by its `performance` JUnit tag (surefire's
`excludedGroups`, cleared by that profile), because it takes minutes, needs a Docker daemon
and asserts nothing. The `-am` matters: the harness lives in the server module and measures
the backend modules, so without it the backends come from the local repository and a change
to one of them is not in the run at all — which is how the first run of the numbers below
was taken twice. Three classes:

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
etcd reported its own disk latency over the run as **0.88 ms** average WAL fsync and
**2.04 ms** average backend commit; no etcd write can be faster than that, and a tuned
cluster on NVMe is several times quicker while three members over a real network are
slower.

Absolute milliseconds moved by up to about 3× between runs on this laptop (its virtualized
disk: the same `PEXPIREAT` case measured 3.9 ms and 12.2 ms on two runs twenty minutes
apart). The call counts did not move at all, and the order of the cases barely moved, which
is why the conclusions below are drawn from those rather than from the milliseconds. Every
number in one table comes from one run, so the comparisons within a table hold.

## What one session costs an application

Stock `RedisIndexedSessionRepository` over Lettuce, one 1 KB attribute, one operation at a
time. "etcd calls" is what that one operation asked etcd, counted from etcd's metrics.

| What the application does | etcd p50 | p95 | p99 | in-memory p50 | etcd calls | of which raft writes |
|---|---|---|---|---|---|---|
| save a new session | 33.4 ms | 64.6 ms | 75.4 ms | 0.43 ms | 16 | 10 |
| save an existing session (a request touching it) | 28.6 ms | 75.1 ms | 99.7 ms | 0.28 ms | 18 | 12 |
| delete a session | 28.1 ms | 35.1 ms | 110.0 ms | 0.51 ms | 23 | 13 |
| change the session id | 24.1 ms | 42.9 ms | 43.9 ms | 0.12 ms | 13 | 8 |
| load a session (`findById`) | 0.46 ms | 0.66 ms | 1.11 ms | 0.06 ms | 1 | 0 |
| find a principal's sessions | 0.77 ms | 1.57 ms | 1.81 ms | 0.10 ms | 2 | 0 |

Reads are cheap and writes are not. A session write costs two orders of magnitude what the
in-memory backend costs, and the reason is entirely in the last two columns — which are the
same as they were before the contention fix, since nothing was added to the write path.

## Why it costs that: raft writes per command

One `KeyValueStore` call, and what etcd was asked for it. `Range` is a read; `Txn`,
`LeaseGrant`, `LeaseRevoke` and `DeleteRange` are raft writes, each one committed to disk on
every member.

| Operation | etcd calls | p50 (etcd) | p50 (in-memory) |
|---|---|---|---|
| `HGETALL` | `Range` 1 | 0.56 ms | 0.00 ms |
| `EXISTS` | `Range` 1 | 0.35 ms | 0.00 ms |
| `PTTL` | `Range` 1 | 0.19 ms | 0.00 ms |
| `HSET`, new session (4 fields, 1 KB) | `Range` 1, `Txn` 1 | 1.02 ms | 0.00 ms |
| `HSET`, one field of an existing session | `Range` 1, `Txn` 1 | 1.08 ms | 0.00 ms |
| `APPEND`, new shadow key | `Range` 1, `Txn` 1 | 0.88 ms | 0.00 ms |
| `SADD`, into a bucket of 1000 | `Range` 1, `Txn` 1 | 1.79 ms | 0.07 ms |
| `SREM`, from a bucket of 1000 | `Range` 1, `Txn` 1 | 2.20 ms | 0.04 ms |
| `DEL` | `DeleteRange` 1 | 1.01 ms | 0.00 ms |
| `PERSIST` | `Range` 1, `Txn` 1, `LeaseRevoke` 1 | 2.87 ms | 0.00 ms |
| **`PEXPIREAT`, key with no TTL yet** | `Range` 1, `LeaseGrant` 1, `Txn` 1 | — | — |
| **`PEXPIREAT`, key that already has one** | `Range` 1, `LeaseGrant` 1, `Txn` 1, `LeaseRevoke` 1 | 3.86 ms | 0.00 ms |
| `RENAME` | `Range` 2, `Txn` 2, `LeaseGrant` 1, `LeaseRevoke` 1 | 8.99 ms | 0.00 ms |

`PEXPIREAT` is the expensive one — three raft writes, because the key moves to a freshly
granted lease and the old lease is revoked — and Spring Session issues **three of them per
save**: on the session, on the shadow key and on the expirations bucket. That is where 9 of
the 12 raft writes of a session touch come from.

So the arithmetic an operator can size with:

> **sessions written per second ≈ (the cluster's raft writes per second) ÷ 12**

On this container etcd sustained roughly 750 calls/s, about 470 of them writes, which is
exactly the ~21 session cycles/s below. A cluster that commits 10,000 writes/s would carry
in the region of 800 session writes/s. Reads do not enter into it.

## Concurrency does not help, and the client's one connection is why

A "cycle" is what a request does: create a session, read it back, touch it.

| Connections | etcd p50 | etcd p99 | etcd cycles/s | in-memory cycles/s | etcd calls per cycle |
|---|---|---|---|---|---|
| 1 | 49.0 ms | 56.1 ms | 20.7 | 1425 | 35 |
| 8 | 370.4 ms | 543.0 ms | 20.8 | 5243 | 35 |
| 32 | 1089.7 ms | 1692.2 ms | 26.2 | 6087 | 35 |
| 128 | 4432.9 ms | 12887.6 ms | 22.0 | 4386 | 34.9 |

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
"20–30 session writes per second per connection, on a cluster that commits a few hundred
writes/s" is the honest reading.

## The one genuinely contended key

Every session expiring in the same minute adds itself to `spring:session:expirations:<minute>`.
Written from many virtual threads inside one adapter, five writes each:

| Writers | p50 | p99 | ops/s | etcd calls per write | Failed |
|---|---|---|---|---|---|
| 4 | 2.37 ms | 11.1 ms | 795 | 0.750 | none |
| 16 | 2.49 ms | 11.4 ms | 3465 | 0.188 | none |
| 64 | 2.79 ms | 4.33 ms | 20,106 | 0.041 | none |
| 256 | 13.0 ms | 15.6 ms | 22,290 | 0.010 | none |

The in-memory backend, same case, reaches 31,550 ops/s at 256 writers — so on the one case
that used to fail, etcd is now within 30% of a map in the same process.

**What it was before**, on the run that found it — same harness, same machine, the etcd
backend without `KeyQueues`:

| Writers | p50 | p99 | ops/s | etcd calls per write | Failed |
|---|---|---|---|---|---|
| 4 | 1.82 ms | 89.8 ms | 199 | 2.8 | none |
| 16 | 2.35 ms | 497.6 ms | 156 | 4.1 | none |
| 64 | 1.90 ms | 1104.7 ms | 242 | 6.9 | none |
| 256 | 2.99 ms | 1955.0 ms | 247 | 15.0 | **244 of 1280 (19%)** |

Two things to read off the pair.

**The failures are gone.** At 256 writers every write landed, where a fifth of them used to
be `EtcdException: ... gave up after 50 attempts because the key kept changing underneath
it` — answered as `ERR internal error` and surfaced by Spring Session as a failed session
save.

**The cost per write now falls with contention instead of growing with it**: 2.8 → 15 calls
before, 0.750 → 0.010 after. That is the whole of the fix. Compare-and-swap makes contention
correct and does not make it scale, so the callers of one adapter no longer compete for a
key — they queue at it, and whichever of them holds it applies everything queued in one
transaction (architecture §11.4). At 256 writers, 1280 writes cost about **13 etcd calls
between them**: some six batches of a couple of hundred each. Throughput follows —
247 → 22,290 writes per second, and a p99 of 15.6 ms where it was two seconds.

Nothing about the answers changed, which is the part that had to be proved rather than
measured: `KeyQueuesTest` holds a batch open rather than hoping for one, and shows that a
batch returns exactly what the same writes made one at a time would have, that a mutation
refusing the value fails its own caller and nobody else, and that a set emptied part-way
through a batch is a new key afterwards. `EtcdKeyValueStoreTest` runs the 256-writer case
itself, so the defect cannot come back unnoticed.

What is *not* fixed, because it cannot be: two adapter **replicas** writing one key still
meet in etcd's compare-and-swap. That contention is bounded by the number of replicas rather
than by the number of application threads, which is the difference between a handful and
hundreds.

## Session size

etcd carries the whole value on every write: `HSET` of one field rewrites the entire hash,
and `SADD` of one member rewrites the entire set.

| Session size | `HSET` new | `HSET` one field | `HGETALL` |
|---|---|---|---|
| 1 KB | 2.94 ms | 2.91 ms | 0.31 ms |
| 10 KB | 1.44 ms | 1.78 ms | 0.24 ms |
| 100 KB | 2.60 ms | 3.88 ms | 0.77 ms |

| Members already in the expirations bucket | `SADD` one more |
|---|---|
| 1 | 1.13 ms |
| 100 | 1.33 ms |
| 1,000 | 1.69 ms |
| 10,000 | 9.88 ms |

Below about ten kilobytes the raft commit dominates so completely that the size does not
show above the run-to-run noise — the 1 KB row here is *slower* than the 10 KB one, which is
the honest way to say "indistinguishable". At 100 KB a difference appears, and it is about
a millisecond. The set is the one that grows into something real: a minute holding 10,000
sessions costs 10 ms per session added, nine times what an empty minute costs. That cost is
per *batch* now rather than per write, but a batch still carries the whole set.

Where it stops fitting, with etcd's default `--max-request-bytes` (1.5 MiB):

| One session attribute | What happened |
|---|---|
| up to 1500 KB | accepted (22.0 ms at 1500 KB) |
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
  to change one field is indistinguishable from writing it new, and reaches about 1 ms extra
  only at 100 KB, a session size the README already advises against. Field-granular keys
  would buy that millisecond and pay for it with a range read per `HGETALL`, a multi-key
  transaction per `HSET` and an expiry that has to be attached to every field. Not a trade
  worth making. The same is **not** true of collections: a set's cost does grow into
  something (10 ms at 10,000 members), so if the expirations bucket is ever redesigned it
  should be for that, not for hashes. One etcd key per member was weighed against batching
  when the contention was fixed and was not needed for it; a bucket's *size* is the one thing
  it would still address.

## What an operator can plan with

- A session write costs **tens of milliseconds** and 12 raft writes against a single-member
  etcd whose commits take 2 ms; a read costs **half a millisecond**.
- **One application connection carries 20–30 session writes per second**, because the
  commands on a connection are served in order. Scale with instances or connections.
- The cluster's own ceiling is **its raft write rate divided by twelve** in session writes
  per second.
- Keep sessions in the tens of kilobytes. It is not the bytes that hurt below that, but
  nothing above a megabyte will be written at all.
- **Contention on one key is no longer something to plan around.** Hundreds of callers
  writing one expirations bucket is now the *cheapest* case per write rather than the one
  that fails, because they are applied a batch at a time. What still grows is the bucket
  itself: a minute holding 10,000 sessions costs 10 ms to write, however few writers there
  are.
