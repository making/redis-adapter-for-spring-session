# 007 — Indexed-mode end-to-end (events + index + cleanup)

## Context
Tie 005 + 006 together and prove the full `@EnableRedisIndexedHttpSession` behaviour against
the adapter: session events, principal index lookup, and the background expiration cleanup
timing that Spring Session relies on. This is the acceptance gate for "full Redis
replacement for Spring Session".

## Depends on
006 (pub/sub, set, keyspace), 005 (data commands), the E2E harness from 005.

## Goal
An automated E2E test where a real Spring Boot app with `@EnableRedisIndexedHttpSession`
(real Lettuce → adapter) observes `SessionCreatedEvent`, `SessionDeletedEvent`,
`SessionExpiredEvent`, and `findByIndexNameAndIndexValue` works.

## Key references
- `.docs/research/02-indexed-command-mapping.md` — §2 keys, §3 which removals notify, §5
  edge cases (RENAME of shadow key, double DEL idempotency, cleanup touches the shadow key).
- `.docs/research/03-pubsub-keyspace-notifications.md` §5 (exact channels/bodies `onMessage`
  expects), §6 (created is published by Spring Session, del/expired are synthesized).
- Spring Session sources: `RedisIndexedSessionRepository.java` (`onMessage`, `saveDelta`,
  `createShadowKey`, `deleteById`, `MinuteBasedRedisSessionExpirationStore`).

## What must work (behaviour, not new commands)
1. **Created**: on new session save, Spring Session `PUBLISH`es to
   `spring:session:event:0:created:{id}` (body = serialized delta). Our server routes it to
   the pattern subscriber `spring:session:event:0:created:*` → `SessionCreatedEvent` fires.
2. **Deleted**: `deleteById` deletes the shadow key `spring:session:sessions:expires:{id}`
   → our `DEL` emits `__keyevent@0__:del` (body = shadow key) → `SessionDeletedEvent`.
3. **Expired**: the shadow key's TTL elapses → `__keyevent@0__:expired` (body = shadow key)
   → `SessionExpiredEvent`. This must fire both when the background cleanup **touches**
   (`EXISTS`) the shadow key and when the active sweeper evicts it.
4. **Index**: authenticated/principal sessions add the id to
   `spring:session:index:...PRINCIPAL_NAME_INDEX_NAME:{principal}` via `SADD`;
   `findByIndexNameAndIndexValue(PRINCIPAL_NAME_INDEX_NAME, principal)` returns them via
   `SMEMBERS`. Removal via `SREM` on delete/expire/principal change.
5. **Cleanup job**: Spring Session's `cleanUpExpiredSessions` (default cron `0 * * * * *`)
   reads the per-minute expirations bucket set and `EXISTS`-touches each shadow key. Verify
   the flow works; in tests trigger `cleanUpExpiredSessions()` directly instead of waiting a
   minute.

## Steps / deliverables
1. Extend the 005 harness with an `@EnableRedisIndexedHttpSession` app and an
   `ApplicationListener`/`@EventListener` capturing session events into a queue.
2. Tests:
   - Create session → assert `SessionCreatedEvent` received with the right id.
   - Set a principal attribute (or `SPRING_SECURITY_CONTEXT`) → assert
     `findByIndexNameAndIndexValue` finds it; delete → index entry gone.
   - Delete session → assert `SessionDeletedEvent`.
   - Short `maxInactiveInterval` → assert `SessionExpiredEvent` fires (drive it two ways:
     let the active sweeper evict, and separately call `cleanUpExpiredSessions()` to force
     the touch path).
   - `changeSessionId` renames both hash and shadow keys; session found under new id.
3. Fix whatever semantics break under a real repository (this task is where subtle timing
   and byte-format mismatches surface). Add regression tests for each.

## Acceptance criteria
- All the above events/lookups pass deterministically (no `Thread.sleep`-only flakiness —
  poll with a bounded timeout / latch).
- No `ConfigureRedisAction.NO_OP` bean is required in the app (our `CONFIG` handling
  satisfies the default `ConfigureNotifyKeyspaceEventsAction`).
- Re-`DEL` / double delete does not double-fire events; missing-key rename is swallowed
  (`ERR no such key`).

## Notes / gotchas
- `del` vs `expired` is chosen by channel; emit on the correct one for the cause.
- Body of del/expired must start with `spring:session:sessions:expires:` or Spring Session
  ignores it (research 03 §5.4) — verify the shadow key name is exactly right.
- The created event body is the app's serialized delta; we only forward bytes, never build
  it.
- Keep this harness green as a permanent regression suite.
