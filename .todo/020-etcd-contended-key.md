# 020 — A contended key must not fail a session save

Read first: `.docs/design/etcd-performance.md`, section "The one genuinely contended key,
and where it breaks", and `.docs/design/architecture.md` §11.4. The code is
`EtcdKeyValueStore.update` and `backOff`.

## Why

Measured on 2026-07-25, writing to one `spring:session:expirations:<minute>` key from
virtual threads inside one adapter:

| Writers | etcd calls per write | Failed |
|---|---|---|
| 4 | 2.8 | none |
| 16 | 4.1 | none |
| 64 | 6.9 | none |
| 256 | 15.0 | **244 of 1280 (19%)** |

`EtcdException: ... gave up after 50 attempts because the key kept changing underneath it`,
which the command layer answers as `ERR internal error` and Spring Session surfaces as a
failed session save. The in-memory backend loses nothing in the same case.

This is a defect rather than a limit. Compare-and-swap on one key does not degrade
gracefully: the work per successful write grows with the number of writers, so past some
concurrency the retries stop keeping up and a share of the writes is simply lost. Raising
`maxAttempts` moves the cliff without removing it, and the p99 at 256 writers is already two
seconds.

It is not reachable through a single Lettuce connection, whose commands the adapter serves in
order, but it is reachable by an application that uses several connections and by several
adapter replicas sharing a cluster — the deployment this backend exists for. Every session
expiring in the same minute lands on the same key, and the optional sorted-set expiration
store is worse: one key for *every* live session.

## Two ways to fix it, and which one is likely right

**Batch what is contending, inside the store (recommended).** All that contends is
read-modify-write on one key, and `update` already takes the mutation as a pure function of
the current value. So the writers on one key can be queued and applied together: one of them
reads the key, applies every queued mutation in order to the value it read, writes the result
in **one** transaction, and hands each caller its own result. That is exactly what serialized
execution would have produced — Redis serializes these anyway — and it turns N concurrent
writes into one raft write instead of N × (attempts). Cross-replica conflicts still exist and
are still handled by the same compare-and-swap, but they are now rare rather than the norm.

Care is needed on: a mutation that throws (`TypeMismatchException` must fail only its own
caller, not the batch), the vanish-to-tombstone path when a set empties mid-batch, per-caller
return values, and not starving a late arrival.

**Store a set as a range of keys instead of one value.** One etcd key per member removes the
contention entirely and makes `SADD` independent of the set's size (7 ms at 10,000 members
today). It is the layout etcd would suggest, and it is a much larger change: `get` becomes a
range read, expiry has to be attached to every member key, and the rename, tombstone and
event rules all have to be restated for a range. Worth considering only if batching turns out
not to be enough.

## Done when

- A failing test comes first: writers on one key from many virtual threads, asserting that
  **every** write lands and none fails. 256 writers × 5 writes reproduces it today.
- The etcd calls per write no longer grow with the number of writers (count them with
  `EtcdMetrics`, as the harness does).
- Nothing about the SPI's semantics changes: the return value of each call, the events, and
  the silence of an emptied set all stay as `EtcdKeyValueStoreTest` has them.
- `.docs/design/etcd-performance.md` is re-run, and §11.4 says what makes contention survive
  now.
