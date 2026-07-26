# 023 — A Cassandra backend

Read first: `CLAUDE.md` §"Adding a new KVS", `.docs/design/architecture.md` §11 (the etcd
backend, which this one is measured against), `README.md` §"Writing a backend" and
`.todo/022-foundationdb-backend.md` (the other spiked candidate; several problems are the
same and one of them Cassandra solves outright).

A spike was run on 2026-07-26 against a real Cassandra before this file was written, so
everything under "What the spike found" is measured rather than reasoned. Nothing was
committed from it; the numbers are here because they are what the decisions below turn on.

## The verdict

**Build it — but decide the layout and the tombstone policy first, because one of them can
sink it.** Cassandra fits the `KeyValueStore` SPI in the places etcd struggles, and it
answers outright the question `022` left open ("who sweeps"). What it does not have is a
watch: it pushes *nothing* about data to a client, so key events become a polled log, and
that is more machinery and worse latency than `EtcdKeyValueStore` gets for free.

One decision to take before any code:

- **`org.apache.cassandra:java-driver-core` is the only transport.** Open-source Cassandra
  has no HTTP API — the CQL native protocol is the whole client surface, and Stargate is a
  separate deployment rather than a library. So the choice §11.1 made for etcd is not
  available here. See "The driver" for what that costs.
- **`spring-boot-starter-data-cassandra` is ruled out** — not on merit, but by the module
  rule: the store module depends on `core` only and never on Spring. The server module
  needs nothing Spring Data provides beyond what four `@ConfigurationProperties` fields and
  a `CqlSession` builder already do.

## What the spike found

Single-node `cassandra:5.0` (reports 5.0.8), RF=1, 1 GB heap, arm64 container; driver
`java-driver-core:4.19.3` in a host JVM. Absolute times are one laptop's and mean little;
the ratios and the limits are the point. **RF=1 on one node is the caveat that matters
most** — see "What the spike did not cover".

| Question | Answer |
|---|---|
| Container ready | **4.8 s** (etcd is faster, FoundationDB needs a port dance; this is ordinary) |
| Point read / single-row write | p50 1.48 ms / 1.87 ms |
| Compare-and-swap (LWT) | p50 4.83 ms — 2.6x a plain write, 4 Paxos round trips |
| A session save (3 writes + 3 deadline pushes) | p50 **8.09 ms** sequential, **3.39 ms** pipelined |
| 4 keys as one cross-partition LOGGED batch | p50 2.51 ms — atomic, **not** isolated |
| 8 fields of one key, single-partition batch | p50 0.92 ms — atomic **and** isolated |
| Value size ceiling | **~16 MiB**, the CQL message limit. 8 MB writes in 23 ms |
| Cross-partition batch ceiling | warns at 5 KiB, **refused over 50 KiB** (`Batch too large`) |
| Single-partition batch | **exempt from that threshold** — 2 MB accepted |
| Add one member to a 50,000-member set | **1.6 ms, flat with size** (etcd: 10 ms at 10,000, growing) |
| Read all 50,000 members back | 59 ms |
| Plain inserts, 256 writers, one partition | **14,738/s, nothing lost, no errors** |
| LWT inserts, 256 writers, one partition | **4,124 of 5,120 failed**, and the node stopped answering |
| Compare-and-swap on one row, 8 / 64 writers | 1.49 / 2.87 attempts per write, nothing lost |
| ...the same at 256 writers | **5,095 of 5,120 failed** (`WriteTimeout ... at SERIAL`) |
| 20,000 members added then removed | the next read of that partition warns |
| 120,000 added then removed | **the read fails outright** (`ReadFailureException`) |
| ...and recovers | only after `gc_grace_seconds` is lowered **and** a compaction runs |
| TTL granularity | whole seconds, and it fires **early**: TTL 2 s was gone at **1.51 s** |
| TTL maximum | 630,720,000 s, but really "no expiry past **2038-01-19**" |
| TTL is per **cell** | an `UPDATE` that forgets `USING TTL` leaves a half-row that still exists |
| A TTL expiry announces | **nothing.** No watch, no CDC a driver can read |
| Polled event log, 500 events | in commit order, none lost, none duplicated, 621/s |
| ...delivery latency at a 25 ms poll | p50 **16.3 ms** |
| ...an idle poll of one empty bucket | 1.21 ms |
| ...an entry stamped 200 ms in the past, written after the cursor passed | **lost** |
| ...trimming the log | a TTL does it; 50 rows with TTL 2 s were gone unaided |
| Deadline index: 100 due out of 500 | 2.0 ms (FoundationDB: 2.8 ms) |
| Sweeping 20,000 due keys | 384 ms (FoundationDB: 459 ms) |
| **A lock with a deadline** | **one statement**: `INSERT ... IF NOT EXISTS USING TTL n`. 16 racers, exactly 1 won, freed itself, a later replica took it |
| A cluster that is not there | fails in **2.0 s** — it does not hang the way FoundationDB does |
| Driver in-flight limit | 1024 per connection; exceeding it is `NodeUnavailableException`, and the spike hit it three times |

### Where Cassandra is better than etcd

- **A collection stops being one value.** One row per hash field and per set member makes
  `SADD` a plain insert: 1.6 ms whether the set holds 100 members or 50,000, against etcd's
  10 ms into a bucket of 10,000 and growing. §11.7 examined this layout for etcd and decided
  against it; here it costs nothing extra because a partition *is* a collection, and it
  removes read-modify-write, compare-and-swap and `KeyQueues` from the write path in one go.
  At 256 concurrent writers to one partition it is 14,738 writes per second with nothing
  lost — the case §11.4 records etcd losing 19% of before batching.
- **TTL is native and free.** It does the job etcd's lease does — collect a key nobody comes
  back to — with no lease to grant, renew or revoke, and so with none of the accounting
  §11.2 exists to justify. `019` (cutting the raft writes a save costs) has no analogue here
  because there is nothing to cut.
- **A lease is one statement.** `INSERT ... IF NOT EXISTS USING TTL n` is mutual exclusion
  *and* a deadline, proved above. `022` names "who sweeps, and the lock with a deadline that
  needs, which is the lease FoundationDB does not have, built by hand" as its largest piece
  of new design; on Cassandra it is one prepared statement.
- **A 16 MiB value**, ten times etcd's 1.5 MiB and a hundred and sixty times FoundationDB's.
  Task `021`'s `ValueTooLargeException` still has something to map (`CQL Message of size ...
  exceeds allowed maximum`), but no realistic session comes near it.
- **`del` versus `expired` stops being guesswork**, for the same reason as FoundationDB: the
  announcement is written by us, so the reason is a field. **No tombstones** in the §11.3
  sense — a removal that must stay silent simply writes no log entry.
- **A cluster that is away fails in two seconds**, so `017`'s health indicator is an
  ordinary query rather than a race against a timeout.

### Where it is worse, and what that forces

- **There is no push of any kind.** This is the one that costs the most. etcd's watch is
  what carries an event across replicas (§11.3) and it is free; Cassandra's native protocol
  pushes only topology and schema, and CDC is commitlog files on each node rather than
  anything a driver can read. So key events become **a polled append-only log** — the same
  shape `022` proved for FoundationDB, minus the counter watch that at least woke it. The
  spike proved it works (500 events, in order, none lost, none duplicated) and what it
  costs: **16 ms of delivery latency at a 25 ms poll, and 1.2 ms per idle poll per replica
  per bucket, for ever**. It also proved where it loses an entry: the clustering key is a
  client-generated `timeuuid`, so **an entry stamped 200 ms in the past, written after the
  reader's cursor had gone by, is never seen**. The reader must lag its cursor behind
  wall-clock by more than the tolerated clock skew and de-duplicate. `WRITETIME` does not
  help — it is the client's clock too.
- **Expiry has to be announced by a sweeper**, since a TTL announces nothing. Smaller than
  the same problem in `022`, in two ways worth writing down: Cassandra still *collects* the
  key, so the sweeper only announces and a late sweep is a late `SessionExpiredEvent` rather
  than a leak; and the lock that elects one sweeper is the one statement above. The scan and
  the sweep are cheap (2.0 ms / 384 ms). What it forces is that the **deadline index rows
  must outlive the data rows** — give them a longer TTL, or the thing to announce is gone
  before anybody announces it.
- **Tombstones, and this is the hazard with no clean answer.** One row per member means one
  Cassandra tombstone per `SREM`, and the spike measured what that does: at 20,000 removals
  in one partition every later read warns, and at **120,000 the read fails outright** until
  `gc_grace_seconds` (10 days by default) has passed and a compaction has run. Spring
  Session churns exactly this way — every save moves a session out of one expirations bucket
  and into the next — and the sorted-set expiration store is worse, because it is one
  partition for ever rather than one per minute. Lowering `gc_grace_seconds` fixes it
  (proved: the read came back after `gc_grace_seconds = 0` and a major compaction) but on a
  multi-node cluster a `gc_grace_seconds` under the hinted-handoff window can **resurrect a
  deleted row** — a session that comes back from the dead, which is the worst failure a
  session store has. This has to be decided, not discovered.
- **LWT is a trap on any hot key.** `INSERT ... IF NOT EXISTS` is exactly the answer `SADD`
  needs ("was this member new?"), and Paxos ballots are **per partition**, so every member
  of one set contends with every other. At 256 writers 4,124 of 5,120 failed and the node
  stopped serving; compare-and-swap on one row at 256 writers failed 5,095 of 5,120. That is
  worse than the etcd case §11.4 calls a defect. So **no LWT on the write path**, which
  means the exact "how many were new" counts of `SADD`/`HSET` have to come from a read
  before the write (cheap, one partition) and are therefore approximate under concurrency,
  or the operation gives up exactness. Spring Session does not read those counts; the SPI
  contract does promise them. Decide which one wins and write it down.
- **Two silent TTL traps.** A TTL is computed from the write's timestamp truncated to whole
  seconds, so **a key can vanish up to a second before its deadline** — the opposite of the
  etcd lease, which §11.2 rounds *up* precisely so that "a key is never collected before it
  is due". Every TTL must be rounded up with a grace. And a TTL lives on the **cell**, not
  the row: one `UPDATE` without `USING TTL` leaves a half-row that still answers `EXISTS`.
  Both were reproduced.
- **Last-write-wins is decided by the client's clock.** Two adapters writing the same cell
  are resolved by timestamp, and the driver's is generated in the JVM. An `SREM` whose
  timestamp skews behind an `SADD` of the same member silently does nothing. §11.7's note
  about clocks is stronger here: on etcd, skew shows up as a key expiring early or late; on
  Cassandra, it can lose a write outright.

### The driver

There is no avoiding a dependency, and it is the stack §11.1 refused:

- **29 jars, 14.4 MB** — netty (7 jars), jackson-databind, jnr-ffi with five ASM modules,
  dropwizard-metrics, HdrHistogram, typesafe-config, reactive-streams. Against etcd's zero
  and FoundationDB's one.
- It is a dependency of **this backend module only** — `core` and `server` are untouched,
  which is the whole point of the split. But a native image for this backend would need
  reachability metadata for all of it, so note in `014` that it is best effort here too
  (`022` already established that a native image is best effort per backend).
- It insists on being told the **local datacenter**; there is no sensible default. And its
  in-flight limit is 1024 requests per connection — the spike knocked its own pool over
  three times with unbounded `executeAsync`, so anything the store does in bulk (the
  sweeper, above all) must bound its own concurrency.

## What to build

The two modules of `CLAUDE.md` §"Adding a new KVS", nothing else touched:
`redis-adapter-for-spring-session-cassandra` (package `am.ik.redis.adapter.cassandra`,
depending on `core` and the driver only) and
`redis-adapter-for-spring-session-server-cassandra` (package
`am.ik.redis.adapter.boot.cassandra`, the four classes). Beyond the checklist there, this
backend specifically needs:

- **the layout, decided and written down first.** One row per hash field and per set member
  is what Cassandra is for and what all the good numbers above come from, with a meta row in
  the same partition holding the type and the deadline so that reading a key is one
  partition read and writing one is a single-partition batch — atomic, isolated and exempt
  from the 50 KiB batch threshold. The open part is **sets that churn**: whether they use
  the same layout with a tombstone policy, or are held as one blob (which trades the
  tombstones for compare-and-swap, and `KeyQueues` with it). Decide per key type if that is
  what the numbers say; a hybrid is allowed, an undecided one is not;
- **a tombstone policy**, named in the design and in `README.md`: `gc_grace_seconds` on each
  table, the compaction strategy, and an explicit statement about the resurrection risk on a
  multi-node cluster. This is the decision most likely to be got wrong quietly;
- **a database index as part of the partition key** — `PRIMARY KEY ((db, k), field)`, proved
  in the spike. One table, independent keyspaces, and the factory still holds nothing until
  `create`;
- **key events**: the polled log, bucketed by time, with the reason in the entry, a cursor
  that **lags wall-clock by the tolerated skew** and de-duplicates, a TTL for the trim, and a
  resync for a replica that fell behind that TTL. The poll interval is a property, and its
  default is a latency-against-cost decision to state;
- **expiry**: TTL on every cell (rounded **up**, with a grace), a deadline index whose rows
  outlive the data, a bounded sweeper that only announces, and the `IF NOT EXISTS USING TTL`
  lock to elect one;
- **no LWT on the write path**, and the `SADD`/`HSET` count question answered explicitly;
- **schema management**: whether the store creates its keyspace and tables, and with what
  replication. A backend that silently creates a `SimpleStrategy` keyspace in production is
  a bug; one that requires DDL by hand is a worse first experience. Probably: create the
  tables, require the keyspace, and say so;
- **consistency levels**, chosen and justified — the polled log in particular is only
  correct if a reader is guaranteed to see what another replica wrote;
- **error mapping**: the CQL message limit and `Batch too large` to `ValueTooLargeException`
  (task `021`), `WriteTimeoutException` at `SERIAL` as retryable, `ReadFailureException` on a
  tombstoned partition to something whose message says *tombstones*, everything else to a
  `CassandraException`;
- **`CassandraBackendProperties`**: contact points, **local datacenter** (no default),
  keyspace, credentials, the `ssl-bundle` the etcd backend already established, the poll
  interval and the sweeper interval;
- **`checkHealth()` from the start**, since a query against an absent cluster already fails
  in two seconds (task `017`).

## What the spike did not cover

Named because each of them could change a decision above, and none of them can be answered
on one node:

- **A real cluster.** Everything above is RF=1 on a single node. Quorum latency, LWT under
  real Paxos across replicas, read repair, and whether the polled log is monotonic for a
  reader on another node are all untested — and the last of those is a *correctness*
  question, not a performance one.
- **Whether the LWT collapse is an artifact.** 256 writers took a 1 GB-heap single node
  down. A real cluster would do better; the design assumes it would not, which is the safe
  direction, but the number should not be quoted as if it were a cluster's.
- **Tombstones over time.** The spike created 120,000 removals in seconds. What matters is a
  bucket that accumulates them over hours under a real compaction strategy.
- **TLS and authentication**, which the etcd backend has and this one would need to match.
- **Cassandra 4.x**, and whether one driver version talks to both. Only 5.0.8 was run.

## Done when

- The layout and the tombstone policy are decided and written down in
  `.docs/design/architecture.md` before the store is written, because everything else
  depends on them.
- Both modules exist, `CassandraKeyValueStoreFactoryTests`, the compatibility run modelled on
  `EtcdBackendEndToEndTests` in indexed mode, and `ReadmeCassandraExamplesTests` all pass;
  `BackendSpiBenchmark` and `SessionPerformanceHarness` run against it under `-Pperformance`
  with a `CallCounter`, so its numbers can be read beside etcd's; `README.md` has its section
  and the two registrations; and `.docs/design/architecture.md` gains a section saying what a
  write, the polled log and the sweeper cost, the way §11 does for etcd.
- A test proves the two TTL traps cannot come back: that a key is never collected before its
  deadline, and that pushing a deadline out leaves no half-row.
- A test proves an event that arrives stamped behind the cursor is still delivered.
- `./mvnw test` passes on a clean checkout with nothing installed by hand.
