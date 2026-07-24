# 006 — Pub/Sub + Set commands + keyspace notifications

## Context
This is the heart of indexed-mode support. Add Set commands, the pub/sub engine, keyspace
notification emission on delete/expiry, and tolerant `CONFIG` handling. After this task the
server can carry `SessionCreated`/`SessionDeleted`/`SessionExpired` traffic; task 007 wires
and verifies the full indexed repository.

## Depends on
005 (data commands + server). Uses the store's `KeyEventListener` from 002.

## Goal
`am.ik.redis.adapter.pubsub` with a process-wide registry, working
`SUBSCRIBE`/`PSUBSCRIBE`/`PUBLISH` over RESP, glob pattern matching, and store-driven
`__keyevent@<db>__:del` / `:expired` emission.

## Key references
- `.docs/research/03-pubsub-keyspace-notifications.md` — **semantics** (§5, §6, §9). Ignore
  the `Subscription`/`onChannelSubscribed` handshake parts: those are the in-process
  `RedisConnection` design; here Lettuce is the client and owns them. We only implement RESP
  wire behaviour + keyspace emission.
- `.docs/design/redis-command-surface.md` §C (expiration/notifications), §D (pub/sub), §E
  (CONFIG).
- `.docs/research/02-indexed-command-mapping.md` §2, §3 (keys, which removals notify).

## Steps / deliverables
1. **Set commands**: `SADD key m [m ...]` → integer added; `SREM key m [m ...]` → integer
   removed; `SMEMBERS key` → array (empty for absent). Members are opaque bytes with
   value-equality.
2. **PubSubRegistry** (shared, owned by the server/store, not per-connection):
   - `Map<channelBytes, Set<ClientConnection>>` and `Map<patternBytes, Set<ClientConnection>>`.
   - `publish(channel, body)`: deliver a `message` push frame to exact-channel subscribers
     and a `pmessage` push to pattern subscribers whose glob matches `channel`; return the
     receiver count. Delivery writes to each subscriber connection's output stream
     (synchronize per-connection writes).
   - A **glob matcher** implementing at least `*` (also `?`, `[...]` if easy).
3. **Pub/sub commands on `ClientConnection`**:
   - `SUBSCRIBE ch [ch ...]` / `PSUBSCRIBE pat [pat ...]`: register, reply one
     `subscribe`/`psubscribe` confirmation frame per channel/pattern with the running count.
     A subscribed connection then only accepts pub/sub commands (and PING) — matching Redis.
   - `UNSUBSCRIBE [ch ...]` / `PUNSUBSCRIBE [pat ...]`: deregister + confirmation frames.
   - On connection close, remove it from the registry.
   - Push frames use RESP3 push type when the connection negotiated RESP3, else RESP2
     arrays (Lettuce handles both).
4. **Keyspace notification emitter**: register a `KeyEventListener` on the store that calls
   `registry.publish("__keyevent@<db>__:del", key)` on delete and
   `"__keyevent@<db>__:expired", key)` on expiry. Body = the key name bytes. Emit
   **synchronously at removal**. Use the DB index the key lives under (default 0).
5. **CONFIG**: `CONFIG GET notify-keyspace-events` → reply the pair
   (`notify-keyspace-events` → e.g. `Egx`) or an empty map — **never error**. `CONFIG SET
   notify-keyspace-events <flags>` → `+OK` (store the value but behaviour is always
   "emit"). `CONFIG GET <other>` → empty is fine.

## Acceptance criteria (tests first)
- Unit: glob matcher (`spring:session:event:0:created:*` matches
  `...created:abc`, not `...other:abc`).
- Registry: subscribe on connection A, publish from connection B → A receives the frame;
  receiver count correct; pattern vs channel routing correct.
- Integration with a real Lettuce pub/sub connection: `psubscribe` a pattern, `publish` to
  a matching channel from another connection, assert the message arrives with correct
  channel + body bytes.
- Keyspace: put a key with a short TTL and subscribe to `__keyevent@0__:expired`; when it
  expires (passively via a touch, and actively via the sweeper) the subscriber receives a
  message whose body equals the key name. `DEL` of a key → `__keyevent@0__:del` with the
  key name.
- `CONFIG GET/SET notify-keyspace-events` behave as specified (no error, `+OK`).

## Notes / gotchas
- The subscriber connection and the publisher connection are different — the registry MUST
  be shared server-wide (research 03 §8).
- Serialize writes to a single connection's output stream: keyspace pushes and normal
  command replies can race on the same socket (a subscribed connection also still answers
  PING).
- Only shadow-key (`spring:session:sessions:expires:*`) del/expired events matter to Spring
  Session, but emitting for all keys is harmless and simpler (research 03 §9.8).
- Keep listener callbacks off the store's internal locks (research 02 note).
