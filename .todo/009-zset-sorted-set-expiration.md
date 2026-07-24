# 009 — ZSet commands for SortedSetRedisSessionExpirationStore (optional)

## Context
Spring Session's default indexed expiration store is the minute-bucket Set-based
`MinuteBasedRedisSessionExpirationStore` (covered by 006/007). Users can opt into the
`SortedSetRedisSessionExpirationStore`, which uses a single sorted set. This task adds the
ZSet commands so that alternative works. **Optional** — only do it if sorted-set expiration
support is wanted.

## Depends on
007 (indexed mode working with the default Set-based store).

## Goal
`ZADD`, `ZREM`, `ZREVRANGEBYSCORE` implemented and a `ZSetValue` type in the store, verified
with an `@EnableRedisIndexedHttpSession` app configured with a
`SortedSetRedisSessionExpirationStore` bean.

## Key references
- `.docs/research/02-indexed-command-mapping.md` §6 — exact ZSet ops, member/score encoding,
  the `reverseRangeByScore(key, 0, now, 0, count)` query shape.
- Spring Session `SortedSetRedisSessionExpirationStore.java`.

## Steps / deliverables
1. Add `ZSetValue` to the `RedisValue` sealed set (member bytes → double score; keep an
   ordering suitable for range-by-score queries).
2. Store operations: add-or-update member score, remove member, range-by-score.
3. Commands:
   - `ZADD key score member [score member ...]` → integer (# added; updates don't count).
   - `ZREM key member [member ...]` → integer removed.
   - `ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]` → members with score
     in `[min, max]`, highest-score-first, honouring `LIMIT offset count`. Spring Session
     calls it as `ZREVRANGEBYSCORE key <now> 0 LIMIT 0 <count>`.
4. Note the behavioural nuance (research 02 §6): the sorted-set store touches the **session
   hash** key, while `SessionExpiredEvent` is still driven by the **shadow key** expiring on
   its own TTL — so no extra keyspace wiring is needed beyond what 006 provides.

## Acceptance criteria (tests first)
- Unit: `ZADD` upsert semantics, `ZREM`, and `ZREVRANGEBYSCORE` ordering + `LIMIT`.
- E2E: an indexed app with a `SortedSetRedisSessionExpirationStore` bean cleans up and
  fires `SessionExpiredEvent` correctly.

## Notes / gotchas
- Score is a `double` (epoch millis of lastAccessed+maxInactive). Match Redis inclusive
  range semantics for `[min,max]`.
- Members are JDK-serialized session-id bytes (opaque) — value-equality as elsewhere.
- If this task is skipped, document in the README that only the default Set-based
  expiration store is supported.
