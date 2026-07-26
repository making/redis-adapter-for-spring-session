# 022 — A FoundationDB backend

Read first: `CLAUDE.md` §"Adding a new KVS", `.docs/design/architecture.md` §11 (the etcd
backend, which this one is measured against) and `README.md` §"Writing a backend".

A spike was run on 2026-07-26 against a real FoundationDB before this file was written, so
everything under "What the spike found" is measured rather than reasoned. Nothing was
committed from it; the numbers are here because they are what the decisions below turn on.

## The verdict

**Build it.** FoundationDB fits the `KeyValueStore` SPI well — better than etcd in three
places — and the two objections that looked like blockers when the spike began are both
answered below, each one proved rather than argued. What is left is ordinary design work.

Two decisions were taken on 2026-07-26 and this task is written on top of them:

- `org.foundationdb:fdb-java` is **accepted as a dependency of this backend**. See "The
  native client" for what that does and does not commit us to.
- **A native image is best effort, per backend.** A backend that cannot be turned into one
  — or that nobody has tried to — is still a backend; a JVM image is the baseline and a
  native image is a bonus where it happens to work. So the JNI in this one is not a cost to
  weigh against building it, and nothing here waits on task 014.

## What the spike found

Single-member `foundationdb/foundationdb:7.3.63`, memory storage engine, arm64 container;
client `org.foundationdb:fdb-java:7.3.63` in a second container on the same docker network.
Absolute times are one laptop's and mean little; the ratios and the limits are the point.

| Question | Answer |
|---|---|
| Point read / single-key commit | 1.79 ms / 3.48 ms |
| A session save (4 keys) as **one** transaction | **5.05 ms** |
| The same 4 keys as 3 separate commits | 17.31 ms |
| Value size ceiling | exactly **100,000 bytes**, then `FDBException 2103` |
| Key size ceiling | 10,000 bytes, then `2102` |
| Transaction size ceiling | 10 MB, then `2101` |
| Transaction age ceiling | 5 s, then `1007` — and `db.run()` **retries that for ever** |
| 8 / 64 / 256 writers on one key | 2.88 / 5.02 / 7.52 attempts per write, **nothing lost** |
| Watch granularity | one key. **There is no range watch**, and no payload — `Void` |
| A watch registered after the writes | misses them; a watch only sees what follows it |
| Watches held at once by one client | 12,000 without complaint (the documented cap is 10,000) |
| Read-then-write against a concurrent write | `1020 not_committed`; the loser retries |
| A read against a cluster that is not there | **never fails** — it waits for ever unless the transaction has a timeout, then `1031` |

### Where FoundationDB is better than etcd

- **A session save is one transaction, not six raft writes.** §11.6 says session writes per
  second are the cluster's raft rate divided by six, and that everything else matters less.
  Here the whole save — hash, two index keys, the deadline — is one commit: 5.05 ms against
  17.31 ms for the same keys written separately. Real multi-key ACID removes the single
  biggest cost of the etcd backend.
- **Contention degrades instead of failing.** §11.4 records 256 writers on one etcd key
  costing 15 calls per successful write with **19% failing outright**. The same case on
  FoundationDB cost 7.52 attempts and lost nothing. Queueing at the key (`KeyQueues`) would
  still be worth having, but it would be an optimization rather than the difference between
  a session being saved and not.
- **`del` versus `expired` stops being guesswork.** etcd cannot say *why* a key went, so
  §11.3 asks every watch for `prev_kv` and infers the reason from the deadline, and invents
  **tombstones** for the two removals that must announce nothing. On FoundationDB the
  announcement is written by us, in the same transaction as the removal — so the reason is
  simply a field, a removal that must stay silent writes no log entry at all, and
  **tombstones are not needed**.

### Where it is worse, and what that forces

- **No TTL of any kind.** etcd's lease is what replaces the in-memory backend's sweeper
  (§11.2); FoundationDB has no equivalent, so expiry becomes entirely the adapter's job: a
  deadline-ordered index plus a sweeper. The spike scanned such an index (100 due keys out
  of 500 in 2.8 ms) and swept 20,000 keys in batches of 500 in 459 ms, so the mechanism is
  cheap. The unsolved part is **who sweeps** when several adapters share one cluster:
  every replica sweeping is duplicated work on a contended range, and one replica sweeping
  needs a lock with a deadline — which is the lease FoundationDB does not have, built by
  hand. This has no analogue in the etcd backend and is the largest piece of new design.
- **No range watch.** A key event has to reach every replica, and `watch` takes one key and
  carries no payload. The spike proved the only shape that works: **one watch on a counter,
  and an append-only log keyed by versionstamp, read as a range from the last entry seen.**
  500 removals reached a second replica in commit order, exactly once, none lost, at 876/s,
  with a wake of about 5 ms. It is sound, but it brings three things the etcd watch gave
  for free: the log must be **trimmed** (it grew 15,390 bytes for 500 events; one
  `clearRange` of the whole thing took 3.6 ms), a replica whose cursor falls behind a trim
  must **detect that and resync** (etcd's compaction problem, ours to build), and the
  counter is a key every replica writes — safe only because `ADD` is an atomic mutation and
  so does not conflict.
- **A 100,000-byte value ceiling — 15× smaller than etcd's.** This is not just a different
  number in the `ValueTooLargeException` mapping of task 021: it means **the etcd layout
  does not transfer**. One `Envelope` per Redis key works there because etcd takes 1.5 MiB;
  a Spring Session hash of over 100 KB is entirely ordinary. So this backend has to either
  chunk a value across keys or store **one FoundationDB key per hash field and per set
  member** — the layout §11.7 examined for etcd and decided against. Here it is not an
  optimization, it is the only thing that fits. Which of the two, and what a chunked value
  does to atomicity, is a decision to make before writing `FoundationDbKeyValueStore`.
- **Five seconds is the whole transaction.** A batch (`KeyQueues`) or a sweeper pass has to
  be bounded, and `db.run()` cannot be trusted with a slow body: `transaction_too_old` is
  retryable, so a body that is always slow retries for ever. The spike hung on exactly that
  and had to be killed. Every transaction needs `setTimeout` and a retry limit, for the same
  reason a read against an absent cluster otherwise waits for ever.

## The native client

There is no avoiding a dependency here. etcd was reachable over its gRPC gateway with the
JDK's `HttpClient` (§11.1); FoundationDB has **no HTTP API at all** — the only client is
the native `libfdb_c`, and the Java binding is a JNI shim over it. What that costs:

- `org.foundationdb:fdb-java` is **one jar with no transitive dependencies whatsoever** —
  nothing like the grpc-netty/protobuf/guava stack §11.1 refused. It is a dependency of
  *this backend module only*; `core` and `server` stay as they are, which is the whole
  point of the module split.
- it needs **`libfdb_c`, which is not in the jar** — the jar carries only the JNI shim
  (`lib/{linux,osx}/{amd64,aarch64}`). 23.9 MB on Linux, 40.9 MB unpacked on macOS. It has
  to be version-matched to the cluster, or the multi-version client used.
- so the container image has to ship it, and a native image would need JNI configuration
  plus that library on top. Native images being best effort per backend, that is a thing to
  try after this works rather than a condition of it.

Two things about loading it that are worth writing down, because they cost an afternoon
otherwise. `FDB_LIBRARY_PATH_FDB_C` is honoured for the **JNI shim only** — `libfdb_c`
itself is resolved by the dynamic loader through an `@rpath` baked into the shim pointing
at Apple's own build machine (`/Users/ec2-user/foundationdb_build_output_macos_arm64/lib`).
But **`System.load` of an absolute path, before anything touches the `FDB` class, is
enough**: the loader then satisfies the shim's reference from what is already in the
process. Proved on macOS aarch64 with no `DYLD_LIBRARY_PATH` and on Linux aarch64 with the
library at `/opt/somewhere/else` and no `LD_LIBRARY_PATH`. So a test needs no environment
variable and no surefire configuration — one `System.load` in a static initializer.

Where the file comes from differs by platform, and only the tests have this problem (a
deployment installs the client the ordinary way):

- **Linux**: the release publishes a bare `libfdb_c.aarch64.so` / `libfdb_c.x86_64.so`
  (23.9 / 23.7 MB) with a `.sha256` beside it. A direct download, no package to unpack.
- **macOS**: only inside `FoundationDB-<version>_<arch>.pkg` (78.9 MB). `xar -xf` then
  `tar -xf` on the clients payload yields `usr/local/lib/libfdb_c.dylib`; both tools are
  present on macOS. Prefer an already-installed `/usr/local/lib/libfdb_c.dylib` and only
  fetch when it is absent.

## Testcontainers: it does work, but not the usual way

A FoundationDB client connects to the coordinator address in the cluster file and
**asserts that the port it reached is the port the server advertises**. Point it at a
remapped published port and it prints
`Assertion pkt.canonicalRemotePort == peerAddress.port failed @ FlowTransport.actor.cpp`
and then times out. So Testcontainers' ordinary "expose a port, read the random mapped
one" does not work.

The way around it is to **make the two ports the same number**: pick a free port on the
host first, and bind it to itself.

```
P = a free host port, chosen at run time
docker run -d -p 127.0.0.1:P:P -e FDB_NETWORKING_MODE=host -e FDB_PORT=P \
  foundationdb/foundationdb:7.3.63
docker exec <id> fdbcli --exec "configure new single memory"
cluster file: docker:docker@127.0.0.1:P
```

Proved end to end from a host JVM against the container, including a **watch** firing over
it, which is the case worth checking because it is a long-lived connection rather than a
request. It is parallel-safe, since P is chosen free at run time rather than fixed — the
one caveat is the usual race between choosing a free port and binding it. From
Testcontainers this is `GenericContainer` plus a `withCreateContainerCmdModifier` setting
an exact `PortBinding`, so the module still follows §3 of `CLAUDE.md`'s "Adding a new KVS"
like every other backend.

`FDB_NETWORKING_MODE=host` is what makes the server advertise `127.0.0.1`; a container that
needs to be reached from *another container* instead (the `examples/` end-to-end, if it
ever runs the app in one) would need the container's own IP and no publishing, which also
works but only where the docker bridge is routable from the host.

## What to build

The two modules of `CLAUDE.md` §"Adding a new KVS", nothing else touched:
`redis-adapter-for-spring-session-foundationdb` (package
`am.ik.redis.adapter.foundationdb`, depending on `core` only) and
`redis-adapter-for-spring-session-server-foundationdb` (package
`am.ik.redis.adapter.boot.foundationdb`, the four classes). Beyond the checklist there,
this backend specifically needs:

- **a value layout that respects 100,000 bytes** — per-field/per-member keys or chunking,
  decided first (above). This is now the *only* open design decision that blocks writing
  `FoundationDbKeyValueStore`;
- **a `FoundationDbNativeClient` in test scope** that finds or fetches `libfdb_c` for the
  running platform, verifies the published sha256 on Linux, caches it outside `target/`,
  and `System.load`s it from a static initializer that every test touching FoundationDB
  runs first. Nothing about it belongs in `src/main`;
- **an `FdbCluster` test fixture** doing the free-port-bound-to-itself dance above, in the
  shape `EtcdCluster` already has, so the suites read like the etcd ones;
- **expiry**: a deadline-ordered index, a bounded sweeper, and an answer to who sweeps
  across replicas;
- **key events**: the counter watch plus versionstamped log the spike proved, with the
  reason (`del` / `expired`) written into the entry, a trim, and a resync for a cursor that
  fell behind one. No tombstones;
- **error mapping**: `2103`/`2101` to `ValueTooLargeException` (task 021 established what
  that is for), `1007`/`1020` as retryable, everything else to a
  `FoundationDbException`;
- **every transaction bounded** by `setTimeout` and a retry limit, so an unreachable
  cluster fails rather than hangs — and `checkHealth()` from the start, since task 017 is
  about exactly this;
- **`FoundationDbBackendProperties`** around a *cluster file* rather than a URL list, which
  is unlike every other backend's connection configuration: the path, or the contents to
  write to a temporary one. Note that `FDB.selectAPIVersion` may be called only once per
  JVM and starts one network thread for the whole process, while `fdb.open()` is nearly free
  (16 handles in 0.3 ms) — so the factory can still hold nothing until `create`, and a
  database index is a key prefix.

## Done when

- The value layout is decided and written down in `.docs/design/architecture.md` before the
  store is written, because everything else depends on it.
- Both modules exist, `FoundationDbKeyValueStoreFactoryTests`, the
  compatibility run modelled on `EtcdBackendEndToEndTests` in indexed mode, and
  `ReadmeFoundationDbExamplesTests` all pass; `BackendSpiBenchmark` and
  `SessionPerformanceHarness` run against it under `-Pperformance` with a `CallCounter`, so
  its numbers can be read beside etcd's; `README.md` has its section and the two
  registrations; and `.docs/design/architecture.md` gains a §12 saying what a transaction,
  the sweeper and the event log cost, the way §11 does for etcd.
- `./mvnw test` passes on a clean checkout on both macOS and Linux with nothing installed
  by hand — no environment variable, no `DYLD_LIBRARY_PATH`, no pre-installed client — and
  says clearly what it is downloading the first time.
- The JVM container image ships `libfdb_c` and the server starts from it. A **native** image
  is explicitly *not* a condition of this task: note in task 014 that this backend would
  need JNI configuration and a 24 MB library, and that failing to build one for it does not
  hold anything up.
- **Not covered by this task, and worth its own once the backend exists**: whether a client
  of one minor version talks to a cluster of another, and whether the multi-version client
  is worth configuring. The spike only ever ran 7.3.63 against 7.3.63.
