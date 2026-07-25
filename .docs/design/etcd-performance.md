# What the etcd backend costs (measured 2026-07-25)

The etcd backend shipped with its correctness proved against a real etcd and its cost not
measured at all. This is the measurement. It is not a benchmark of etcd, and it is not a
promise: it is one machine's numbers, taken by a harness that is in the repository so that
anyone can take their own.

It has already paid for itself twice. The first run found a contended key that lost a fifth
of its writes, and the fix for it is measured below; the same run's arithmetic said that
two thirds of a session save was spent replacing etcd leases that could have been renewed,
and that fix is measured below too — a save costs half the raft writes it did. Both of the
"what it was before" tables are kept, because a number is only worth something beside the
one it replaced.

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
etcd reported its own disk latency over the run as **0.90 ms** average WAL fsync and
**3.67 ms** average backend commit; no etcd write can be faster than that, and a tuned
cluster on NVMe is several times quicker while three members over a real network are
slower.

Absolute milliseconds moved by up to about 3× between runs on this laptop (its virtualized
disk: the same `PEXPIREAT` case measured 3.9 ms and 12.2 ms on two runs twenty minutes
apart, and the backend commit above was 2.04 ms on an earlier one and 3.67 ms on this).
The call counts did not move at all, and the order of the cases barely moved, which is why
the conclusions below are drawn from those rather than from the milliseconds. Every number
in one table comes from one run, so the comparisons within a table hold; a millisecond
compared across two runs holds only when it moved further than the disk did, which is said
where it is done.

## What one session costs an application

Stock `RedisIndexedSessionRepository` over Lettuce, one 1 KB attribute, one operation at a
time. "etcd calls" is what that one operation asked etcd, counted from etcd's metrics.

| What the application does | etcd p50 | p95 | p99 | in-memory p50 | etcd calls | of which raft writes |
|---|---|---|---|---|---|---|
| save a new session | 13.8 ms | 35.4 ms | 38.0 ms | 0.46 ms | 15 | 8 |
| save an existing session (a request touching it) | 8.84 ms | 11.1 ms | 12.9 ms | 0.28 ms | 15 | 6 |
| delete a session | 14.5 ms | 17.2 ms | 19.4 ms | 0.52 ms | 22 | 11 |
| change the session id | 9.99 ms | 12.7 ms | 14.7 ms | 0.12 ms | 13 | 8 |
| load a session (`findById`) | 0.46 ms | 0.61 ms | 0.76 ms | 0.06 ms | 1 | 0 |
| find a principal's sessions | 0.60 ms | 0.85 ms | 1.92 ms | 0.10 ms | 2 | 0 |

Reads are cheap and writes are not. A session write still costs two orders of magnitude
what the in-memory backend costs, and the reason is entirely in the last two columns.

**What it was before** the leases were renewed rather than replaced — same harness, same
machine, on a run whose disk was *faster* than this one's (2.04 ms per backend commit
against 3.67 ms):

| What the application does | etcd p50 | etcd calls | of which raft writes |
|---|---|---|---|
| save a new session | 33.4 ms | 16 | 10 |
| save an existing session | 28.6 ms | 18 | 12 |
| delete a session | 28.1 ms | 23 | 13 |
| change the session id | 24.1 ms | 13 | 8 |

The row that matters is the second one, because it is what every request after the first
does: **twelve raft writes became six**, and a save that took 28.6 ms takes 8.84 ms on a
slower disk. Changing the session id is untouched — it is a `RENAME`, which moves a lease
rather than pushing a deadline out — and that is the shape of the whole change: it is
about `PEXPIREAT` and nothing else.

## Why it costs that: raft writes per command

One `KeyValueStore` call, and what etcd was asked for it. `Range` is a read; `Txn`,
`LeaseGrant`, `LeaseRevoke` and `DeleteRange` are raft writes, each one committed to disk on
every member.

| Operation | etcd calls | raft writes | p50 (etcd) | p50 (in-memory) |
|---|---|---|---|---|
| `HGETALL` | `Range` 1 | 0 | 0.20 ms | 0.00 ms |
| `EXISTS` | `Range` 1 | 0 | 0.19 ms | 0.00 ms |
| `PTTL` | `Range` 1 | 0 | 0.17 ms | 0.00 ms |
| `HSET`, new session (4 fields, 1 KB) | `Range` 1, `Txn` 1 | 1 | 0.89 ms | 0.00 ms |
| `HSET`, one field of an existing session | `Range` 1, `Txn` 1 | 1 | 1.27 ms | 0.00 ms |
| `APPEND`, new shadow key | `Range` 1, `Txn` 1 | 1 | 1.35 ms | 0.00 ms |
| `SADD`, into a bucket of 1000 | `Range` 1, `Txn` 1 | 1 | 3.32 ms | 0.09 ms |
| `SREM`, from a bucket of 1000 | `Range` 1, `Txn` 1 | 1 | 5.10 ms | 0.05 ms |
| `DEL` | `DeleteRange` 1 | 1 | 2.34 ms | 0.00 ms |
| `PERSIST` | `Range` 1, `Txn` 1, `LeaseRevoke` 1 | 2 | 9.91 ms | 0.00 ms |
| **`PEXPIREAT`, same TTL pushed out again** | `Range` 1, `LeaseKeepAlive` 1, `Txn` 1 | **1** | 1.33 ms | 0.00 ms |
| `PEXPIREAT`, key with no TTL yet | `Range` 1, `LeaseGrant` 1, `Txn` 1 | 2 | — | — |
| `PEXPIREAT`, a TTL that really changed | `Range` 1, `LeaseGrant` 1, `Txn` 1, `LeaseRevoke` 1 | 3 | — | — |
| `RENAME` | `Range` 2, `Txn` 2, `LeaseGrant` 1, `LeaseRevoke` 1 | 4 | 15.5 ms | 0.00 ms |

`PEXPIREAT` used to be the expensive one in every case: three raft writes, because the key
was moved onto a freshly granted lease and the old lease revoked, three times per save — 9
of the 12 raft writes a session touch cost. It is now **one** in the case Spring Session
actually issues, and the reason is in the first row: a key whose lease already renews to the
TTL the new deadline needs keeps that lease, and etcd renews a lease on the leader alone,
committing nothing to raft. The measurement was taken twice to be sure of that, once by
counting the calls above and once by watching
`etcd_server_proposals_committed_total`, which five `LeaseKeepAlive`s left where it was and
five `LeaseGrant`s raised by five.

The TTL is the same on every request because Spring Session asks for it in *relative* terms
— `EXPIRE key 1800`, not an absolute deadline — so what the adapter computes is a whole
number of seconds every time (`RedisSessionExpirationPolicy` issues all three that way).
A deadline that really moves the TTL still costs the old three, which is the row below it,
and the first `PEXPIREAT` on a key still has to grant.

So the arithmetic an operator can size with:

> **sessions written per second ≈ (the cluster's raft writes per second) ÷ 6**

A cluster that commits 10,000 writes/s would carry in the region of 1,600 session writes/s
where the same cluster used to carry 800. Reads do not enter into it.

One thing measured and *not* built: answering a `PEXPIREAT` whose deadline falls in the same
second as the current one without writing at all. It would be wrong. The deadline in the
value is exact to the millisecond and every read honours it, so not writing it means a
session that expires up to a second early and a `PTTL` that answers with the old deadline —
and it would not help anyway, since Spring Session's deadline moves forward by a few
milliseconds on every single request and is never the same twice.

## Concurrency does not help, and the client's one connection is why

A "cycle" is what a request does: create a session, read it back, touch it.

| Connections | etcd p50 | etcd p99 | etcd cycles/s | in-memory cycles/s | etcd calls per cycle |
|---|---|---|---|---|---|
| 1 | 18.8 ms | 24.5 ms | 51.8 | 1129 | 31 |
| 8 | 151.5 ms | 172.9 ms | 53.0 | 4972 | 31 |
| 32 | 819.4 ms | 884.5 ms | 39.4 | 6344 | 31 |
| 128 | 3534.8 ms | 7708.5 ms | 31.3 | 4481 | 31 |

Before the leases were renewed the same table read 20.7, 20.8, 26.2 and 22.0 cycles/s at
35 calls per cycle: flat, at a third to a half of what it is now. Latency still grows
linearly with the number of callers, which is the signature of a queue. Two things put it
there, and neither is a defect:

- **Spring Session shares one Lettuce connection**, and the adapter serves one connection
  with one virtual thread running a strict request/response loop — RESP requires the replies
  in order, and Redis behaves the same way. So the commands of 128 application threads are
  executed one after another. At 0.05 ms a command that is invisible; at 16 ms a session
  save it is the whole story.
- **etcd's write rate is the floor underneath that**: the calls per cycle stayed at 31
  whatever the concurrency, so nothing was wasted on retries here — the requests were simply
  waiting.

What did change shape is that throughput now *falls* as the callers pile up (53 cycles/s at
8 connections, 31 at 128) where it used to be flat. With a third of the raft writes to wait
for, what a caller waits on is no longer only the disk, and a queue 128 deep costs
something to hand around. It is still above the old figure at every width.

What follows for a deployment: more application instances (or a Lettuce connection per
worker) multiply this, one application instance on one connection does not. Planning on
"30–50 session writes per second per connection, on a cluster that commits a few hundred
writes/s" is the honest reading.

## The one genuinely contended key

Every session expiring in the same minute adds itself to `spring:session:expirations:<minute>`.
Written from many virtual threads inside one adapter, five writes each:

| Writers | p50 | p99 | ops/s | etcd calls per write | Failed |
|---|---|---|---|---|---|
| 4 | 2.65 ms | 16.2 ms | 662 | 0.750 | none |
| 16 | 2.78 ms | 9.64 ms | 3384 | 0.188 | none |
| 64 | 4.29 ms | 6.90 ms | 13,346 | 0.041 | none |
| 256 | 10.6 ms | 15.1 ms | 21,251 | 0.012 | none |

The in-memory backend, same case, reaches 29,842 ops/s at 256 writers — so on the one case
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
before, 0.750 → 0.012 after. That is the whole of the fix. Compare-and-swap makes contention
correct and does not make it scale, so the callers of one adapter no longer compete for a
key — they queue at it, and whichever of them holds it applies everything queued in one
transaction (architecture §11.4). At 256 writers, 1280 writes cost about **15 etcd calls
between them**: some seven batches of a couple of hundred each. Throughput follows —
247 → 21,251 writes per second, and a p99 of 15.1 ms where it was two seconds.

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
| 1 KB | 3.37 ms | 3.24 ms | 0.42 ms |
| 10 KB | 1.26 ms | 1.56 ms | 0.27 ms |
| 100 KB | 2.72 ms | 8.18 ms | 1.02 ms |

| Members already in the expirations bucket | `SADD` one more |
|---|---|
| 1 | 2.79 ms |
| 100 | 3.89 ms |
| 1,000 | 6.18 ms |
| 10,000 | 13.7 ms |

Below about ten kilobytes the raft commit dominates so completely that the size does not
show above the run-to-run noise — the 1 KB row here is *slower* than the 10 KB one, which is
the honest way to say "indistinguishable". At 100 KB a difference appears, and it is a few
milliseconds. The set is the one that grows into something real: a minute holding 10,000
sessions costs five times what an empty minute costs to add to. That cost is per *batch* now
rather than per write, but a batch still carries the whole set. Nothing here was touched by
the lease change, which is why these numbers moved with the disk and not with the design.

Where it stops fitting, with etcd's default `--max-request-bytes` (1.5 MiB):

| One session attribute | What happened |
|---|---|
| up to 1500 KB | accepted (18.9 ms at 1500 KB) |
| 1600 KB, 2000 KB | refused: `etcdserver: request is too large` |
| 4000 KB | refused by the gateway: `grpc: received message larger than max (4096428 vs. 2097152)` |

Two things worth knowing about that. The limit applies to the request etcd decodes, not to
the JSON the adapter sends, so the base64 the value travels as (four bytes on the wire per
three of session) costs bandwidth but does not lower the ceiling. And both refusals now
reach the application as `ERR value too large for the backend`, with etcd's reason in the
adapter's log: the client layer raises the SPI's `ValueTooLargeException` for a refusal
whose text is about size, and the command layer gives that an error of its own rather than
`ERR internal error` — which was accurate, since the write did not happen, but sent whoever
was holding the exception looking for a bug in the adapter instead of for a smaller session.

## What this says about the two optimizations the design left out

Architecture §11.7 listed two things deliberately not built. The measurement decided them,
and one of the two has since been built:

- **Lease renewal instead of grant-and-revoke — built, and it bought what it promised.** The
  first run's prediction was that a save would fall from 12 raft writes to "around 6" and
  that this would be a factor of two in sessions per second. It is exactly 6, and the factor
  turned out nearer three on the wire: 28.6 ms per save became 8.84 ms *on a slower disk*,
  and 20.7 request cycles per second became 51.8. What it cost is a byte of format version
  and a `long` in every value (architecture §11.2), because the TTL a key's lease renews to
  has to travel with the key — the replica pushing a deadline out is not always the one that
  granted the lease.
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

- A session write costs **ten milliseconds or so** and 6 raft writes against a single-member
  etcd whose commits take 3 ms; a read costs **half a millisecond**.
- **One application connection carries 30–50 session writes per second**, because the
  commands on a connection are served in order. Scale with instances or connections.
- The cluster's own ceiling is **its raft write rate divided by six** in session writes per
  second.
- Keep sessions in the tens of kilobytes. It is not the bytes that hurt below that, but
  nothing above a megabyte will be written at all.
- **Contention on one key is no longer something to plan around.** Hundreds of callers
  writing one expirations bucket is now the *cheapest* case per write rather than the one
  that fails, because they are applied a batch at a time. What still grows is the bucket
  itself: a minute holding 10,000 sessions costs five times an empty one to add to, however
  few writers there are.
