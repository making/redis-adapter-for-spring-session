# 002 — KeyValueStore SPI + in-memory backend

## Context
The `KeyValueStore` SPI is the single pluggable seam every future backend implements
(architecture.md §4). This task defines it and ships the in-memory (`ConcurrentHashMap`)
reference implementation with TTL and Redis-style expiration semantics. Pure backend logic
— **no networking, no RESP, no Spring** here.

## Depends on
001.

## Goal
A tested `am.ik.redis.adapter.store` package: the SPI + `InMemoryKeyValueStore` supporting
STRING / HASH / SET values, absolute per-key TTL, passive + active expiration, and
delete/expire event callbacks.

## Key references
- `.docs/design/architecture.md` §4 (SPI shape), §7 (scaling note).
- `.docs/design/redis-command-surface.md` §A (types), §C (expiration), §F (edge cases).
- `.docs/research/02-indexed-command-mapping.md` §2 (key formats/types), §4 (opaque bytes).

## Steps / deliverables
1. `RedisValue` — sealed interface with records `StringValue(byte[])`,
   `HashValue(Map<ByteArrayKey,byte[]>)`, `SetValue(Set<ByteArrayKey>)`. (`ZSetValue` is
   added in task 009 — leave the sealed set open to it or add a permit stub.)
2. `ByteArrayKey` (or reuse one wrapper) — value-equal wrapper over `byte[]` with correct
   `equals`/`hashCode`; **required** because raw `byte[]` uses identity equality
   (research 04 §2).
3. `KeyValueStore` interface — the minimal contract. At least:
   - typed access sufficient for the command layer: get the `RedisValue` for a key (or
     type-specific getters), replace/merge hash fields, add/remove set members, set/append
     a string;
   - `boolean delete(byte[] key)` (returns whether it existed and fires `onDeleted`);
   - `boolean exists(byte[] key)` — honours passive expiry;
   - TTL: `expireAt(key, long epochMilli)`, `persist(key)`, `Long getExpireAt(key)`;
   - `void addKeyEventListener(KeyEventListener)`.
   - Keep signatures Redis-agnostic; do not leak RESP concepts into the SPI.
4. `KeyEventListener { void onExpired(byte[] key); void onDeleted(byte[] key); }`.
5. `InMemoryKeyValueStore implements KeyValueStore`:
   - Backed by `ConcurrentHashMap<ByteArrayKey, Entry>` where `Entry` holds the
     `RedisValue` + optional expiry epoch-milli.
   - **Passive expiry**: every read/exists/mutate checks the entry's expiry; if elapsed,
     remove it and call `onExpired` *before* returning "absent".
   - **Active expiry**: a background sweeper on a virtual thread
     (`Thread.ofVirtual()...` / a single-thread scheduled loop) periodically evicts expired
     keys and fires `onExpired`. Make the interval configurable (e.g. 1s) and the sweeper
     stoppable (`close()`), because tests need determinism.
   - Emit `onDeleted` synchronously inside `delete(...)` only when the key existed.
   - Thread-safe for concurrent connections (many virtual threads will hit it).
   - Multi-DB: either a single keyspace for now, or `InMemoryKeyValueStore` per DB index
     with the DB index carried alongside events. Start with DB 0; keep the door open.

## Acceptance criteria (tests — write these first, watch them fail)
- STRING: `append` on an absent key creates a zero-length value; second `append` grows it.
- HASH: put fields then read back the exact bytes; missing key → empty.
- SET: add/remove/members round-trip with value-equality on members.
- TTL passive: set expiry in the past, then `exists`/read → false/absent **and**
  `onExpired` fired exactly once with the right key bytes.
- TTL active: set a short TTL, do **not** access the key, assert `onExpired` fires within
  a bounded time via the sweeper (use a small interval + Awaitility-style poll or a latch).
- `delete` fires `onDeleted` once for an existing key, and not at all for an absent key.
- `persist` cancels a pending expiry (no event afterwards).
- Concurrency smoke: many virtual threads mutate disjoint keys without loss.

## Notes / gotchas
- Values are opaque blobs; never decode them. Only keys/fields are text (and only if you
  choose to log them).
- Do not fire `onExpired` twice for the same key if both passive access and the sweeper
  race — guard removal atomically (`compute`/`remove(key, expectedEntry)`).
- The event listener will later publish keyspace notifications; keep the callback fast and
  do not hold the map lock while invoking it.
