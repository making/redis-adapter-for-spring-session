# 024 — A DynamoDB backend

Read first: `CLAUDE.md` §"Adding a new KVS", `.docs/design/architecture.md` §11 (the etcd
backend, which this one is measured against), `README.md` §"Writing a backend", and
`.todo/022-foundationdb-backend.md` / `.todo/023-cassandra-backend.md` — the other two spiked
candidates. All three end up needing the same two things (a polled event log and a sweeper),
and the reasons are different each time.

Two spikes were run on 2026-07-26, both before this file was written, so everything under "What
the spike found" is measured rather than reasoned. Nothing was committed from either.

- **DynamoDB**, through the Floci emulator (`io.floci:testcontainers-floci:2.12.0`), because
  AWS publishes no DynamoDB you can run.
- **ScyllaDB Alternator** (`scylladb/scylla:latest`), which serves the same API and *is* a real
  database in a container. It was spiked because it removes two of DynamoDB's three objections
  outright — no per-request bill and no emulator — and then measured its way out of scope. The
  numbers are kept in the appendix, because the reason not to use it is worth not
  re-discovering.

**The target is real DynamoDB** (decided 2026-07-26). Everything below assumes it.

## The verdict

**Build it. It is the best of the three candidates, and the two things that make it so are
both features Alternator does not have** — which is why targeting DynamoDB rather than the
intersection is the decision this file is written on.

- **A session save is one `TransactWriteItems`, and the transaction costs what one write
  costs**: 4 items measured at 2.14 ms against a single `PutItem`'s 2.15 ms, so the whole save
  is 2.11 ms against 12.07 ms done the way the etcd backend does it. §11.6 says a session save
  is six raft writes and that nothing else matters as much; here it is one round trip.
- **The removal and its announcement are written together, atomically, and doing so is cheaper
  than not doing so** (5.82 ms against 7.32 ms for the two writes apart). The `del`-versus-
  `expired` guesswork of §11.3 goes and the **tombstones** go with it: the reason is a field we
  write, and a removal that must announce nothing simply writes no log entry.

Two things have to be decided before any code, and one of them is a trap the local test
environment actively hides. Three decisions are taken here:

- **`software.amazon.awssdk:dynamodb` is accepted as a dependency of this backend**, on
  `url-connection-client` rather than the default HTTP clients. See "The SDK".
- **DynamoDB's own TTL is a garbage collector, never the expiry.** AWS deletes expired items
  **within 48 hours, best effort**, while the emulator collects them about a second after their
  deadline. A backend written and tested locally would look correct and would leak every
  abandoned session in production. Expiry is the adapter's, exactly as in `022`.
- **Key events do not come from Streams.** They come from a log table written in the same
  transaction as the removal, and polled. See "Why not Streams".

## What the spike found

`floci/floci:latest` through `io.floci:testcontainers-floci:2.12.0` (Testcontainers 2.0.5 — the
version Spring Boot 4.1.0 manages), AWS SDK v2 `2.49.3` over `url-connection-client`, arm64,
client in a host JVM. **Absolute latencies are one laptop's and one emulator's; throughput
numbers are the emulator's and are not quoted at all** (it tops out around 500 writes/s, which
says nothing about DynamoDB). The ratios, the limits and the semantics are the point.

| Question | Answer |
|---|---|
| Container ready | **2.5 s** (10.9 s the first time, pulling the image) |
| `CreateTable` with a GSI and a stream / enabling TTL | 104 ms / 7 ms |
| Point read / single-item write | p50 1.90–2.39 ms / 2.15 ms |
| Conditional write (compare-and-swap) | p50 2.22 ms — **the same as a plain write** |
| `TransactWriteItems` of 4 items | p50 2.14 ms — **the same as one `PutItem`** |
| `BatchWriteItem` of 25 items | p50 2.93 ms |
| A session save: 3 writes + 3 deadline pushes, sequentially | p50 12.07 ms |
| ...the same 3 writes with the deadline folded into each | p50 5.70 ms |
| ...as **one** `TransactWriteItems` | p50 **2.11 ms** |
| Item size ceiling | **400 KB**. Largest value payload accepted: **409,585 bytes**, then `Item size has exceeded the maximum allowed size` |
| `TransactWriteItems` ceiling | 100 items; 101 refused |
| Two operations on one item in one transaction | refused — `Transaction request cannot include multiple operations on one item` |
| `BatchWriteItem` ceiling | 25 items; 26 refused |
| Add one member to a set of 0 / 1,000 / 10,000 / 50,000, one item per member | 2.62 / 2.42 / 2.58 / 4.94 ms — **flat with size** |
| Read all 50,000 members back | 6 pages, 188 ms |
| The same set held as **one blob**, read-modify-write | 19 KiB 7.65 ms, 195 KiB 12.27 ms, **1 MB refused** |
| Plain `PutItem`, 8 / 64 / 256 writers, one partition, distinct sort keys | **nothing lost, no errors** at any of them |
| Compare-and-swap on one item, 8 / 64 / 256 writers | 2.51 / 10.56 / **19.31** attempts per write; at 256, **4,139 of 5,120 lost** |
| `PutItem` with `ReturnValues=ALL_OLD` | says **exactly** whether that member was already there |
| A transaction of conditional puts where one condition fails | cancels **all** of them (`[None, ConditionalCheckFailed]`); the one whose condition held was not written |
| `HSET` of 8 fields: 8 `PutItem`s with `ReturnValues` / one transaction / `Query` then one transaction | 18.22 / **2.81** / 4.64 ms |
| `HGETALL` of an 8-field hash | 1.90 ms — one `Query` of one partition |
| `DEL` of an 8-field hash | 4.93 ms — one `Query` plus one `TransactWriteItems` |
| Deadline index (GSI): 100 due out of 500 | 2.81 ms (FoundationDB 2.8 ms, Cassandra 2.0 ms) |
| Sweeping 20,000 keys in batches of 25, concurrency 1 / 16 / 64 | 10.1 s / 4.2 s / 3.8 s |
| **A lock with a deadline** | **one conditional `PutItem`**. 16 racers, exactly 1 won; once it lapsed a later replica took it |
| A removal **and** its log entry in one `TransactWriteItems` | 5.82 ms — **cheaper than the same two writes separately** (7.32 ms) |
| Polled log, 500 entries, two replicas | both saw **500 of 500**, in commit order, **no duplicates** |
| ...delivery latency at a 25 ms poll | p50 **15.0 ms**, p99 30.2 ms |
| ...an idle poll of one empty bucket | 2.39 ms |
| ...an entry stamped 300 ms in the past, written after the cursor passed | **lost** — exactly the hazard `023` found |
| ...32 replicas polling one bucket | no errors |
| A table that is not there | fails in **1.83 s**; 0.00 s with retries off and a call timeout |
| `until` as an attribute name | a **reserved word**. Conditions need `ExpressionAttributeNames` |
| `software.amazon.awssdk...model.Record` | ambiguous against `java.lang.Record` on Java 25 — must be qualified |

### Where DynamoDB is better than etcd, and than the other two candidates

- **The transaction, and the atomic announcement** — the two headline results above. `022` and
  `023` both arrive at "write the reason as a field", but on FoundationDB it is a versionstamped
  log with a counter watch and on Cassandra it is a batch that is atomic but not isolated. Here
  it is one call that costs nothing extra. `019`, which exists to cut the six raft writes a
  save costs, has no analogue.
- **A lease is one conditional `PutItem`**, proved with 16 racers. This is `022`'s largest open
  design problem and `023`'s one-statement answer, and it is what elects a single sweeper.
- **A collection stops being one value.** One item per hash field and per set member makes
  `SADD` a plain `PutItem`: flat from an empty set to 50,000 members, against etcd's 10 ms into
  a bucket of 10,000 and growing (§11.7). At 256 concurrent writers to one partition nothing was
  lost and nothing errored — the case §11.4 records etcd losing 19% of before batching.
  `HGETALL` becomes one `Query`.
- **The exact count `SADD` and `HSET` promise is actually available.** `PutItem` with
  `ReturnValues=ALL_OLD` answers "was this member new?" per item. `023` left this undecided for
  Cassandra; here it is a decision with a measured price (18.22 ms for 8 fields against
  2.81 ms), not an unknown.
- **Nothing to operate.** No cluster, no compaction, no `gc_grace_seconds` (`023`'s worst
  hazard, where a wrong value resurrects a deleted session), no native library and no port
  dance (`022`'s). On-demand capacity means the operator provisions nothing.
- **A table that is away fails in under two seconds**, so `017`'s health indicator is an
  ordinary call rather than a race against a timeout — unlike FoundationDB, which hangs.

### Where it is worse, and what that forces

- **The TTL is a garbage collector, and the local emulator lies about it.** This is the most
  dangerous finding in the file. AWS deletes an expired item **within 48 hours, best effort**,
  and says so: expired items keep showing up in reads, queries and scans, and AWS's own advice
  is to filter them out in the client. Floci collected them **about one second after the
  deadline, never early, five times out of five**. So expiry is the adapter's job, entirely, as
  in `022` — a deadline attribute that every read compares against, a deadline-ordered index,
  and a sweeper. The TTL attribute is still written, but only so that storage for a key no
  sweeper ever reached is eventually reclaimed. **It must never be what fires `onExpired`**, and
  a test has to hold that line, because the environment the test runs in will not.
- **There is no push, and Streams cannot be made into one.** See below; this is what costs the
  most.
- **1,000 write units per partition, whatever the table's capacity.** A Redis key maps to a
  partition key, and Spring Session has exactly one genuinely hot key: every session expiring in
  the same minute joins that minute's set. So that bucket is capped at ~1,000 writes/s by a
  service limit no amount of provisioning lifts, and adaptive capacity does not help a single
  partition key. The layout has to **shard a bucket across partitions** — a suffix on the
  partition key derived from the member, with the reads fanning out over the shards. This has no
  analogue in the other two spikes and it is the second decision to take before any code.
- **400 KB per item**, so a set cannot be one blob past roughly 20,000 small members (195 KiB
  worked, 1 MB was refused). Per-item members are forced — which is also what makes the
  contention result above true, so this is a constraint and a gift at once. `021`'s
  `ValueTooLargeException` has a clear thing to map.
- **Compare-and-swap collapses under contention, exactly as on etcd.** 19.31 attempts per write
  at 256 writers and four fifths of them lost. The emulator's low ceiling exaggerates the
  collapse, but attempts-per-write growing linearly with writers is optimistic concurrency's
  shape, not the emulator's. `KeyQueues` (§11.4) is already the answer and this backend needs the
  same thing — but with per-item members there is far less left to contend for.
- **A transaction is all-or-nothing about its conditions**, so conditions cannot be used to
  count: a transaction of conditional puts in which one member already existed cancelled every
  put, including the ones whose own condition held. Exact counts therefore mean N individual
  writes (6.5x), and the middle option — `Query` the partition, then one transaction, 4.64 ms —
  is exact only as far as the caller is serialized, which within one adapter `KeyQueues` already
  guarantees. Decide and write it down.
- **Money is a design input, and no other backend has had one.** Every request is billed:
  $0.625 per million writes, $0.125 per million eventually-consistent reads, transactional
  writes at **2x** (and a cancelled transaction still costs). So the transaction that makes a
  save one round trip also doubles what that save costs — cheap in latency, not free in money,
  and both facts belong in `README.md`. The polled log is the part that hurts most, because it
  is billed while nothing is happening: one replica polling one bucket every 25 ms
  strongly-consistent is 3.46 M reads a day, about **$26 per replica per month for an idle
  system**, halved by reading eventually-consistently. The poll interval is therefore not just a
  latency knob, it is a price.
- **GSI reads are eventually consistent, always.** Fine for the sweeper — a late sweep is a late
  `SessionExpiredEvent` — but the deadline index can never be what a read consults. Every
  passive-expiry decision comes from the item's own deadline attribute, read strongly consistent
  from the base table.

### Why not Streams

Streams is the only thing DynamoDB has that resembles etcd's watch, and it does not fit. The
spike confirmed it works — two readers of one shard saw identical records, in commit order, with
no duplicates, and a reader starting at `TRIM_HORIZON` saw the whole history — and AWS promises
exactly-once and per-item ordering. It still cannot be the transport:

- **At most two processes may read one shard**, and one for a global table; beyond that AWS
  throttles. There is one shard **per table partition**, so a replica that wants every key event
  must read every shard — and the third adapter replica breaks the limit on all of them. This is
  a hard service quota, not a tuning problem.
- **Shards split, have lineage, and expire after 24 hours**, so a correct reader has to discover
  shards, order parents before children, and resync when it falls behind. That is what the
  Kinesis Client Library is for, and the KCL is a large dependency plus a lease table of its own
  — for a backend whose whole appeal is that it depends on one SDK over the JDK's own HTTP
  client.
- **The one property that would have made Streams worth the trouble cannot be tested locally.**
  On AWS a TTL deletion arrives with `userIdentity` `principalId dynamodb.amazonaws.com`, type
  `Service`, which is how you would tell an expiry from a delete. Floci **never emitted a stream
  record for a TTL removal at all** (90 s of waiting), so that distinction is unprovable in the
  test environment. It is also unnecessary once the reason is written by us as a field.

So the transport is the **polled log**, for the third time in three spikes — and this is the one
place DynamoDB is worse than etcd rather than better. What the spike proved about it is above:
two replicas, 500 entries, none lost, none duplicated, in order, p50 15 ms at a 25 ms poll, and
the same clock hazard `023` found — an entry stamped 300 ms in the past and written after the
reader's cursor had gone by is never seen. The cursor must lag wall-clock by more than the
tolerated skew and de-duplicate. Unlike Cassandra there is no TTL that will trim the log
promptly, so trimming is an explicit ranged delete by the elected sweeper.

### The SDK

- **28 jars, 6.9 MB**, of which only three are not AWS's: `slf4j-api`, `reactive-streams` and
  `eventstream`. **No Netty, no Jackson, no Guava** — `third-party-jackson-core` is shaded inside
  the SDK. Against etcd's zero dependencies, FoundationDB's one jar and Cassandra's 29 jars and
  14.4 MB with seven Netty modules.
- That is with `netty-nio-client` and `apache5-client` excluded and `url-connection-client` used
  instead — the whole spike ran on it, so the backend talks to DynamoDB over
  `HttpURLConnection`, which is the same spirit as §11.1's choice of the JDK's `HttpClient` for
  etcd. Left alone, `dynamodb` pulls **42 jars and 13 MB**, ten of them Netty. The exclusions are
  not optional.
- It is a dependency of **this backend module only**; `core` and `server` are untouched.
- Bound every client explicitly. The default failed against an absent endpoint in 1.83 s
  (acceptable); with `RetryPolicy.none()` and an `apiCallTimeout` it was immediate. The store
  must choose, not inherit.

## The store under the tests is a fake, and that is new

`CLAUDE.md` says a store that talks over a network "is proven against the real thing, not a
fake", and the etcd backend keeps that promise — `quay.io/coreos/etcd` in a container is a real
etcd. **This backend cannot keep it.** AWS does not publish DynamoDB as an image; every local
option — Floci, DynamoDB Local, anything else — is a reimplementation. The one real database
that speaks the API is Alternator, and the appendix records why it cannot serve as the test
store for a backend built on transactions. So the rule is broken here whatever we do, and the
task is to be explicit about where the fake and the real thing part company rather than to
pretend otherwise.

Floci earns its place: it was faithful to every documented limit the spike probed — 400 KB
items, 100-item transactions, 25-item batches, the refusal of two operations on one item in a
transaction, reserved words, `ReturnValues`, all-or-nothing transaction cancellation. Those are
the semantics most of the backend is made of, and a suite against Floci really does prove them.
It starts in 2.5 s, which is faster than etcd.

Four things it does **not** prove, each of which has to be handled by construction and named in
the design:

1. **TTL timing.** Floci ~1 s, AWS up to 48 h. Handled by never depending on it (above).
2. **TTL removals in a stream.** Floci emits none. Moot once Streams is not the transport.
3. **The per-partition 1,000 WCU limit and throttling.** The emulator throttles nothing, so
   `ProvisionedThroughputExceededException` never appears in a local run; the retry and backoff
   path for it has to be unit-tested against an injected failure.
4. **Anything about throughput**, or about what a transaction really costs against a real
   commit.

So: the compatibility suite and every semantic test run on Floci in the ordinary build — which
keeps `./mvnw test` working on a clean checkout with nothing but Docker — and there is
additionally **an opt-in suite against a real table** (a system property naming a table and a
region, skipped otherwise) that covers precisely the four above. The opt-in suite is not a
condition of this task, but the file has to exist and be documented, or the gap quietly becomes
an assumption.

## What to build

The two modules of `CLAUDE.md` §"Adding a new KVS", nothing else touched:
`redis-adapter-for-spring-session-dynamodb` (package `am.ik.redis.adapter.dynamodb`, depending
on `core` and the SDK only) and `redis-adapter-for-spring-session-server-dynamodb` (package
`am.ik.redis.adapter.boot.dynamodb`, the four classes). Beyond the checklist there:

- **the layout, decided and written down first.** One item per hash field and per set member,
  with a meta item in the same partition holding the type and the deadline, so that reading a key
  is one `Query` and writing one is a single `TransactWriteItems` — atomic, and measured at the
  price of a single write. `PRIMARY KEY (pk, sk)` with `pk` carrying the database index, so
  databases are independent keyspaces and the factory still holds nothing until `create`. The
  open part is **how an expirations bucket is sharded across partitions** to get past 1,000
  writes/s, and what that does to reading one back;
- **expiry**: a deadline attribute every read compares against, a deadline-ordered GSI, a bounded
  sweeper elected by the conditional-`PutItem` lease, and a DynamoDB TTL attribute written
  **only** as a storage backstop, rounded **up** so nothing is reclaimed before it is due. A test
  must prove that a key is never treated as present after its deadline and never collected before
  it, and that neither depends on the TTL firing;
- **key events**: the log entry written in the same `TransactWriteItems` as the removal with the
  reason (`del` / `expired`) as a field, bucketed by time; a cursor that **lags wall-clock by the
  tolerated skew** and de-duplicates; an explicit trim by the elected sweeper; and a resync for a
  replica that fell behind a trim. No tombstones. The poll interval is a property, and its
  default is a latency-against-**cost** decision to state in `README.md`;
- **the count question answered explicitly** — `Query`-then-transaction, exact under `KeyQueues`
  within one adapter and approximate across replicas, or N writes with `ReturnValues` for
  exactness at 6.5x. Pick one, say which, and say what the SPI's promise means under concurrency;
- **`KeyQueues`, or a deliberate decision not to.** Per-item members remove most of what one
  adapter contends with; the compare-and-swap collapse above is what is left;
- **the two limits respected by construction**: at most 100 items in a transaction and 25 in a
  batch, so a hash or a set larger than that is split — and splitting a transaction means the
  write is no longer atomic, which has to be a stated consequence rather than a surprise;
- **error mapping**: `Item size has exceeded the maximum allowed size` to `ValueTooLargeException`
  (task `021`), `ProvisionedThroughputExceededException` and `TransactionCanceledException` with a
  `TransactionConflict` reason as retryable with backoff, `ConditionalCheckFailedException` as the
  ordinary compare-and-swap loss, everything else to a `DynamoDbBackendException`;
- **`DynamoDbBackendProperties`**: table name, region, endpoint override (which is what points a
  test or a local run at an emulator), credentials or the default provider chain, the poll
  interval, the sweeper interval, and the request/retry bounds. Validate in the compact
  constructor and name the property in every message;
- **schema management**: whether the store creates the table and its GSI. Probably create it if
  absent, on-demand billing, and say so — a backend that requires DDL by hand is a bad first
  experience, and unlike Cassandra there is no replication strategy to get wrong;
- **`checkHealth()` from the start** (task `017`), since a call against an absent table already
  fails in under two seconds;
- **`README.md` has to carry one operator-facing thing no other backend needed**: what this costs
  — the transaction's 2x, and the poll interval's standing charge.

## Its tests

Per `CLAUDE.md` §3, plus what is specific here:

- a `FlociContainer` fixture in the shape `EtcdCluster` already has — one container for every
  test class in the JVM, tests separated by a key prefix of their own rather than by a container
  each. `io.floci:testcontainers-floci` in test scope, **not** `spring-boot-testcontainers-floci`,
  which would put Spring on the store module's test path;
- pin the image tag rather than `latest`, so a failure is reproducible, and move it deliberately
  — the same rule `EtcdCluster` follows;
- `DynamoDbKeyValueStoreFactoryTests`, the compatibility run modelled on
  `EtcdBackendEndToEndTests` in **indexed** mode, and `ReadmeDynamoDbExamplesTests`;
- a test that a key is never announced as expired by DynamoDB's own TTL — that is, that the
  sweeper is what fires it — because the emulator will happily make the wrong design pass;
- a test that an event stamped behind the cursor is still delivered;
- the opt-in real-AWS suite described above, skipped unless a system property names a table;
- `DynamoDbSpiPerformanceTests` / `DynamoDbSessionPerformanceTests` under `-Pperformance` with a
  `CallCounter`, and a note in the report that the numbers are an emulator's — they are comparable
  to the in-memory baseline in *shape* (calls per operation) but not in latency. Calls per Spring
  Session operation is the number worth reporting here, because it is also the bill.

## Done when

- The layout — including how a hot bucket is sharded — and the expiry design are written into
  `.docs/design/architecture.md` before the store is written, because everything else depends on
  them.
- Both modules exist and every suite above passes; `README.md` has its `### dynamodb` section,
  the two registrations, and an honest paragraph about cost and about the poll interval;
  `.docs/design/architecture.md` gains a section saying what a save, the polled log and the
  sweeper cost, the way §11 does for etcd, and stating plainly that the tests run against an
  emulator and what that does not cover.
- `./mvnw test` passes on a clean checkout with nothing installed by hand beyond Docker.
- `examples/session-example-dynamodb` runs the example against the emulator, and `014` gains a
  note that a native image for this backend needs reachability metadata for the SDK (best effort
  per backend, as `022` established).

## What the spike did not cover

Named because each could change a decision above:

- **Real DynamoDB.** Everything above is an emulator plus documentation. Throttling, per-partition
  limits, TTL timing, GSI propagation delay and the real cost of a transaction are all untested.
- **Whether sharding a bucket is enough.** 1,000 WCU per partition is documented; what the
  adapter's write pattern actually does to a partition is not measured.
- **The polled log's read cost at scale.** 32 replicas polling one bucket was fine on the
  emulator; on AWS the same case is bounded by 3,000 RCU on that partition and by the bill.
- **TLS and authentication** beyond the default credentials chain, which the etcd backend has and
  this one would need to match, plus IAM: which policy the adapter needs is documentation no other
  backend required.
- **A second emulator as a second opinion.** Running the same suite against a different emulator
  would catch the places where Floci is the only thing that agrees with us. **Decided
  2026-07-26: the second opinion is kumo (`ghcr.io/sivchari/kumo`), not DynamoDB Local — and it
  is the second opinion, never the primary.** Its DynamoDB implementation (~7,300 lines of Go)
  was read against every limit the spike probed. The core is genuinely there:
  `TransactWriteItems` is a two-phase, all-or-nothing transaction with `CancellationReasons`
  (the feature whose absence disqualified Alternator), and the 100-item transaction ceiling,
  the 25-item batch ceiling, GSIs, `Query` by `IndexName`, `ReturnValues=ALL_OLD`, condition
  expressions and a TTL reaper (30 s tick) are all implemented. Three checks are missing, each
  permissive in exactly the direction that disqualified Alternator — code proved against it
  would be rejected by DynamoDB:
  - **no 400 KB item ceiling** at all, so the `ValueTooLargeException` mapping (task `021`)
    cannot be proven against it;
  - **no refusal of two operations on one item in one transaction** (only "exactly one action
    per member" is checked), so a save that touches one item twice passes kumo and fails AWS;
  - **no reserved-word list**, so a condition naming `until` bare passes without
    `ExpressionAttributeNames`.
  The kumo suite is therefore opt-in, pointed at the emulator by endpoint override, and a
  `GenericContainer` (no Testcontainers module exists for it). All three gaps are small
  patches, worth contributing upstream — with them kumo beats DynamoDB Local as the second
  opinion: single binary, fast start, CI-shaped.

---

## Appendix — ScyllaDB Alternator, measured and set aside

Alternator serves the DynamoDB API from a real ScyllaDB, in a container, answering `ListTables`
**5 seconds** after `docker run`. It would have fixed the two things this file is least happy
about: the test store would be a real database rather than an emulator, and the polled log's
standing charge would be nothing. It was spiked on the same day, with the same code, in both of
its relevant write-isolation modes. **It is out of scope**, and this is the reason, kept so the
question is not reopened from first principles.

**`TransactWriteItems` is not implemented** — `Unsupported operation TransactWriteItems`. That is
precisely the feature the verdict above rests on: the one-round-trip session save and the atomic
removal-plus-announcement. A backend targeting both stores would have to give both up and write
to the intersection, which is what `022` and `023` already had to do.

Two further differences run in the dangerous direction — Alternator is *more* permissive, so code
proved against it would be rejected by DynamoDB:

| | DynamoDB | Alternator |
|---|---|---|
| Item size ceiling | 400 KB, enforced | **none found** — 1 MB accepted (976 KiB blob: 78.72 ms p50, 435 ms p99) |
| `BatchWriteItem` ceiling | 25 | **26 accepted** (its default is 100) |

What it does well, for the record:

| Question | Alternator |
|---|---|
| `PutItem` | **1.91 ms** with `only_rmw_uses_lwt`, 5.00 ms with `always_use_lwt` |
| `GetItem` / `Query` one partition / `BatchWriteItem` 25 | 2.04–2.38 / 1.78 / 2.48 ms |
| Conditional `UpdateItem` | 3.79 / 5.49 ms |
| A session save, deadline folded in / concurrent | 4.88 / **2.30 ms** |
| A removal and its log entry, two writes | 4.96 ms (never atomic) |
| Read 50,000 members back | 6 pages, **97.9 ms** (Floci: 188 ms) |
| Sweeping 20,000 keys, concurrency 1 / 16 / 64 | 6.4 / 1.0 / **0.8 s** (Floci: 10.1 / 4.2 / 3.8 s) |
| A lock with a deadline | identical — 16 racers, 1 won, a later replica took the lapsed lease |
| Polled log, 500 entries, two replicas | 500 of 500 each, in order, no duplicates, p50 **14.0 ms**, idle poll 1.44 ms, 32 replicas at 4,814 polls/s with no errors |
| TTL with `--alternator-ttl-period-in-seconds=1` | +584 to +618 ms after the deadline, never early (default: **24 h**) |
| An endpoint that is not there | 2.26 s |

And where it is worse:

- **Compare-and-swap collapses far harder than DynamoDB's**, because Paxos ballots are per
  partition exactly as `023` found for Cassandra: **219.05 attempts per write at 256 writers,
  3,262 of 5,120 lost, 3 writes per second** (DynamoDB: 19.31 and 4,139 lost; etcd §11.4: 15 and
  19%). At 64 writers it was already 47.44 attempts and 200 lost.
- **Streams is less usable than DynamoDB's**, which is saying something: **32 shards on a single
  node**, a reader of one shard saw **0 of 500** records, a `TRIM_HORIZON` reader saw 25 records of
  history, and the documentation records a **10-second visibility delay** by default.
- **Write isolation is a deployment requirement with a sharp edge.** `only_rmw_uses_lwt` is what
  the good numbers above come from, but it is only correct if an item that is ever written
  conditionally is **never** written unconditionally; `always_use_lwt` is always safe and 2.6x
  slower on every write.

**If Alternator is ever wanted**, it is a variant of this backend rather than a flag on it: no
transactions, so the session save is three concurrent writes (2.30 ms, close enough), and the
announcement becomes **verifiable rather than atomic** — the log reader confirms the key really is
gone before announcing it, at one `GetItem` per event. That is a sound design and arguably a
better one, since it also covers an announcement written for a removal that then failed. It is
simply not the design that DynamoDB's transactions make available, and this task takes the
DynamoDB one.

**Fixture note, if it ever matters:** `org.testcontainers:scylladb` has exactly what is wanted —
`withAlternator()` and `getAlternatorEndpoint()` — but it stops at **1.21.4**, the Testcontainers
1.x line, while this project is on **2.0.5**. So it would be a `GenericContainer`. Two things cost
time otherwise: Alternator binds to the container's own address rather than to `0.0.0.0`, and a
stray process holding the host port produces a misleading `405 Method Not Allowed` rather than a
connection refusal.
