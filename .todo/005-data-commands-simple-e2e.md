# 005 — Data commands + simple-mode end-to-end

## Context
Implement the data commands that back `@EnableRedisHttpSession` (the simple, TTL-based
`RedisSessionRepository`) and prove it works end to end with a real Spring Session app
driving a real Lettuce client against our server.

## Depends on
002 (store), 003 (codec), 004 (server + dispatcher).

## Goal
Session create / read / update / delete / expire works for `@EnableRedisHttpSession`
through the adapter, verified by an automated E2E test.

## Key references
- `.docs/research/01-non-indexed-command-mapping.md` — the exact 6 ops and their commands.
- `.docs/design/redis-command-surface.md` §B (simple mode), §F (edge cases).

## Commands to implement (bind `CommandDispatcher` → `KeyValueStore`)
- `HGETALL key` → all field/value pairs; **empty (not null) for a missing key**.
- `HMSET key f v [f v ...]` → `+OK`. Also implement `HSET key f v [...]` → integer
  (# new fields); Lettuce/Spring Data may emit either.
- `EXISTS key [key ...]` → integer count (uses store passive expiry).
- `DEL key [key ...]` → integer removed.
- `RENAME old new` → `+OK`; **if `old` missing → `-ERR no such key`** (exact prefix;
  research 02 §5). Preserve the value type and TTL on rename.
- `PEXPIREAT key ms-epoch` → `:1`/`:0`.
- `PERSIST key` → `:1`/`:0`.
- (Defensive extras, cheap: `EXPIRE`/`EXPIREAT`, `PTTL`/`TTL`, `HGET`, `HDEL`, `TYPE`.)

Type checking: a command used on the wrong type replies `-WRONGTYPE Operation against a key
holding the wrong kind of value`. Spring Session never mixes types, so this is just
correctness hygiene.

## Steps / deliverables
1. Implement the handlers above, delegating to the store. Keep argument parsing/validation
   in the handler; keep storage semantics in the store.
2. Wire a `KeyValueStore` instance into `RedisAdapterServer` (constructor/factory).
3. **E2E test harness** (test scope in `core`, or a dedicated test source set):
   - Add test-scoped deps: `spring-boot-starter-web`, `spring-session-data-redis`,
     `spring-boot-starter-data-redis` (Lettuce), `spring-boot-starter-test`. These are
     testing libraries (allowed by the "no external deps except testing" rule).
   - A `@SpringBootTest` app annotated `@EnableRedisHttpSession`, with
     `spring.data.redis.host/port` pointing at a `RedisAdapterServer` booted on an
     ephemeral port (start it in a `@BeforeAll` / test `@Configuration`).
   - Exercise via `SessionRepository`/`FindByIndexNameSessionRepository` or an MVC endpoint
     that sets/reads a session attribute.

## Acceptance criteria (tests first)
- Create a session, set attributes, `save` → read it back in a fresh `findById`; all
  attributes and timestamps survive the JDK-serialized round trip byte-for-byte.
- `findById` of an unknown id → null (empty `HGETALL`).
- `deleteById` removes it (`EXISTS` → 0 afterwards).
- Changing the session id issues `RENAME` and the session is found under the new id and
  gone under the old.
- TTL: a session with a short `maxInactiveInterval` becomes unreadable after it elapses
  (store passive expiry via the `EXISTS`/`HGETALL` path), i.e. `findById` returns null.
- Unit tests for each handler against the store (independent of the network).

## Notes / gotchas
- Simple `save()` command order is `RENAME` (if id changed) → `HMSET` → `PEXPIREAT`
  (research 01 §1). Do not reorder assumptions into the store.
- `PEXPIREAT` is milliseconds since epoch (not relative). `PEXPIRE` (relative, task 006) is
  a different command.
- Keep the harness reusable — task 007 extends it for indexed mode.
