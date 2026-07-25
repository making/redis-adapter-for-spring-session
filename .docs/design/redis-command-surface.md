# Redis command surface for Spring Session

The exact, minimal set of Redis behaviours the adapter must implement so that stock Spring
Session Data Redis (driven by a real Lettuce client) works against it. Distilled from
`.docs/research/01..04`. When in doubt, the research files have `file:line` citations into
the Spring Session and Spring Data Redis sources.

All command arguments and replies are **bytes**. Only key names and hash-field names are
UTF-8 text; values / hash-values / set-members / pub-sub bodies are **opaque JDK-serialized
blobs** — store and return them byte-for-byte, never parse them.

## A. Keys and data types Spring Session creates

Defaults: namespace `spring:session:`, database `0`.

| Logical entity | Key format | Type | TTL |
|---|---|---|---|
| Session hash | `spring:session:sessions:{id}` | HASH | `PEXPIRE` = (maxInactive + 5min)·1000 (indexed) / `PEXPIREAT` = lastAccessed+maxInactive (simple) |
| Shadow "expires" key | `spring:session:sessions:expires:{id}` | STRING (empty) | `PEXPIRE` = maxInactive·1000; `PERSIST` if maxInactive<0 (indexed only) |
| Principal index set | `spring:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:{principal}` | SET | none (shrunk by `SREM` only) |
| Expirations bucket set | `spring:session:expirations:{epochMillisRoundedToMinute}` | SET | `PEXPIRE` = (maxInactive+5min)·1000; `DEL` on cleanup |
| Expirations sorted set (opt-in) | `spring:session:sessions:expirations` | ZSET | none (shrunk by `ZREM` only); **replaces** the bucket sets when a `SortedSetRedisSessionExpirationStore` bean is declared |
| Created channel | `spring:session:event:0:created:{id}` | pub/sub | — |
| Deleted keyevent (subscribed) | `__keyevent@0__:del` | pub/sub | — |
| Expired keyevent (subscribed) | `__keyevent@0__:expired` | pub/sub | — |

Hash field names: `creationTime`, `lastAccessedTime`, `maxInactiveInterval`,
`sessionAttr:{name}`. Field values are JDK-serialized (the class-javadoc showing
plaintext decimals is illustrative only — the wire carries binary blobs).

The `0` in channel names is the DB index. Do not hard-code it: derive channel names from
the same DB the connection is on.

## B. Data commands

Method names in the research are Spring Data Redis `RedisConnection` methods; the wire
command is what Lettuce actually sends and what we must parse.

### Simple mode (`@EnableRedisHttpSession`) — research 01

| Wire command | Behaviour | Reply |
|---|---|---|
| `HGETALL key` | all field/value pairs of the hash; empty hash for a missing key | array (RESP2) / map (RESP3) |
| `HMSET key f v [f v ...]` | set all fields | `+OK` |
| `HSET key f v [f v ...]` | set all fields (Lettuce may use either; support both) | integer (# new fields) |
| `EXISTS key [key ...]` | count existing (honours passive expiry) | integer |
| `DEL key [key ...]` | delete; **fires `del` keyevent per removed key** | integer (# removed) |
| `RENAME old new` | rename; **if `old` is missing, reply error whose message starts exactly with `ERR no such key`** | `+OK` / error |
| `PEXPIREAT key ms-epoch` | set absolute expiry (ms) | integer 1/0 |
| `PERSIST key` | clear TTL | integer 1/0 |

Notes: simple-mode `save()` order is `RENAME` (only if id changed) then `HMSET` then
`PEXPIREAT`. `findById` is `HGETALL`. `deleteById` is `DEL`. `save` first does `EXISTS`.

### Indexed mode adds (`@EnableRedisIndexedHttpSession`) — research 02

| Wire command | Behaviour | Reply |
|---|---|---|
| `PEXPIRE key ms` | relative expiry in ms (indexed TTL path uses PEXPIRE, **not** EXPIRE) | integer 1/0 |
| `APPEND key value` | append; **creating an empty STRING key if absent** (value is `""` = 0 bytes); key then reports `EXISTS`=true and accepts PEXPIRE/PERSIST/DEL/RENAME | integer (new length) |
| `SADD key member [member ...]` | add to set | integer (# added) |
| `SREM key member [member ...]` | remove from set | integer (# removed) |
| `SMEMBERS key` | all members; empty/absent → empty | array |
| `PUBLISH channel message` | route to subscribers (see D); returns receiver count | integer |

Robustness aliases worth implementing even if not observed: `EXPIRE`/`EXPIREAT` (seconds),
`PTTL`/`TTL`, `HGET`, `HDEL`, `TYPE`, `UNLINK`. Keep them cheap; do not block on them.

### Sorted-set expiration store (opt-in) — research 02 §6

An application may declare a `SortedSetRedisSessionExpirationStore` bean instead of the
default minute buckets. Spring Session then keeps every live session in one sorted set
scored by `lastAccessed + maxInactive` (epoch millis, as a `double`) with the session id —
serialized, so opaque — as the member.

| Wire command | Behaviour | Reply |
|---|---|---|
| `ZADD key score member [score member ...]` | add, or move a member already there to its new score | integer (# added, not counting moves) |
| `ZREM key member [member ...]` | remove members; an emptied sorted set is gone (no `del` keyevent, as with `SREM`) | integer (# removed) |
| `ZREVRANGEBYSCORE key max min [WITHSCORES] [LIMIT offset count]` | members scored in `[min, max]`, highest score first, ties by member bytes descending; `(` prefixes an exclusive end, `+inf`/`-inf` an open one; a negative count means all | array |

Sent as `ZREVRANGEBYSCORE key <now> 0 LIMIT 0 <cleanupCount>` (default 100). Scores arrive
the way a client prints a `double`, so an epoch millisecond is written `1.7534567891E12`.
The `ZADD` options (`NX`, `XX`, `GT`, `LT`, `CH`, `INCR`) are rejected rather than ignored.

Behavioural note: this store touches the **session hash** key, not the shadow key, so its
cleanup only forces a stale hash to be reclaimed. `SessionExpiredEvent` still comes from
the shadow key expiring on its own TTL, so §C is unchanged and nothing extra is wired.

## C. Passive & active expiration (critical for indexed mode)

Spring Session's `SessionExpiredEvent` and `SessionDeletedEvent` are driven **entirely by
keyspace notifications**, not by direct calls. Therefore:

1. **On `DEL` of a key that existed → emit `__keyevent@<db>__:del`, body = the key name.**
2. **On TTL expiry → emit `__keyevent@<db>__:expired`, body = the key name.** Expiry must
   be observed both:
   - **passively**: any access (`EXISTS`, `HGETALL`, …) to a key whose TTL has elapsed
     evicts it and emits `expired` *at that moment*; and
   - **actively**: a background sweeper evicts+emits for keys never accessed.
3. The background cleanup job in Spring Session issues `EXISTS` ("touch") on the shadow key
   `spring:session:sessions:expires:{id}` to force lazy expiry; deletion issues `DEL` on
   it. Only shadow-key (`...:sessions:expires:...`) del/expired events matter — Spring
   Session filters others out (body must start with `spring:session:sessions:expires:`).
   Emitting keyevents for *all* keys is harmless; emitting for the shadow key is required.
4. `del` vs `expired` is selected by **channel** by the listener — emit on the correct
   channel for the removal cause. Re-`DEL` of an already-absent key emits nothing
   (idempotent).

Do **not** just drop expired keys silently — that breaks session events.

## D. Pub/Sub — research 03 (semantics only; Lettuce owns the client side)

- A **process-wide** registry maps channels and patterns to subscriber connections
  (subscriber and publisher are different connections).
- `SUBSCRIBE`/`PSUBSCRIBE`/`UNSUBSCRIBE`/`PUNSUBSCRIBE`: standard RESP behaviour — reply
  with the confirmation frames (`subscribe`/`psubscribe`/… , channel/pattern, count) and,
  while subscribed, push `message` (channel delivery) and `pmessage` (pattern delivery)
  frames. Lettuce turns these into Spring's `RedisMessageListenerContainer` callbacks.
- `PUBLISH channel body`: deliver to exact-channel subscribers and to **glob-pattern**
  subscribers whose pattern matches `channel`; return receiver count. `PUBLISH` on a
  non-subscribed connection is normal (`convertAndSend`).
- Pattern matching must implement Redis glob `*` (the created pattern is
  `spring:session:event:0:created:*`); `?` and `[...]` are nice-to-have.
- The `created` message is published **by Spring Session itself** (`convertAndSend`) — we
  only route it. The `del`/`expired` messages are **synthesized by our store** (§C).

## E. Handshake / server commands — research 04 §3 + empirical

Lettuce performs a handshake on connect and pings periodically. The server must satisfy at
least: `PING` (→ `+PONG` or echo), `HELLO [proto]` (return the server-info map; negotiate
RESP2/RESP3), `AUTH` (accept when no password configured, or validate when configured),
`CLIENT SETINFO`/`CLIENT SETNAME`/other `CLIENT` subcommands (→ `+OK`), `SELECT db`
(→ `+OK`; sets the connection DB used for keyspace channel names), `QUIT` (→ `+OK`, close),
`COMMAND`/`COMMAND DOCS`/`COMMAND COUNT` (minimal/empty reply). Pin the exact set by
booting the server and connecting a real Lettuce client (task 004): log any unrecognized
command and implement until the handshake and session flows pass.

Empirically (Lettuce 7.5.2, task 004): a default client sends `HELLO 3`, then `SELECT` if
the URI names a database, `CLIENT SETNAME` if it names a client name, `CLIENT SETINFO
lib-name` and `CLIENT SETINFO lib-ver`, and `CLIENT ID` / `PING` on demand. A client pinned
to RESP2 sends **no** `HELLO` at all, so a connection must default to RESP2 rather than
wait to be told.

**Authentication.** With a password configured, every command except `AUTH`, `HELLO` and
`QUIT` is answered `NOAUTH Authentication required.` until that connection authenticates.
Credentials arrive as `AUTH [username] password` or as
`HELLO <protover> AUTH <username> <password>` — a client configured with credentials uses
the latter — and a password sent on its own authenticates the user `default`. Wrong
credentials get `WRONGPASS invalid username-password pair or user is disabled.`; a `HELLO`
with no credentials on a protected server gets `NOAUTH HELLO must be called with the client
already authenticated, otherwise the HELLO <proto> AUTH <user> <pass> option can be used to
authenticate the client and select the RESP protocol version.` A rejected `HELLO` changes
nothing about the connection, including its protocol version. With no password configured
the server is open and `AUTH` replies `+OK` to anything, so a client that carries a
password still connects.

`CONFIG GET notify-keyspace-events` must return a non-null reply (e.g. the pair
`notify-keyspace-events` → `Egx`, or an empty reply) and **must not error**; `CONFIG SET
notify-keyspace-events <flags>` must reply `+OK`. Our store always emits keyevents, so the
value is behaviourally irrelevant — only the calls must succeed. (Alternatively the app
declares `ConfigureRedisAction.NO_OP`, but we should not require that.)

## F. Edge cases checklist

- `RENAME` of a missing source → error message begins `ERR no such key` (Spring Session
  swallows exactly that; other errors are rethrown).
- `HGETALL` of a missing key → empty (Spring Session reads empty as "session gone").
- `APPEND key ""` on a missing key → creates a zero-length STRING key.
- Principal set has no TTL; dangling ids are tolerated (`findByIndexNameAndIndexValue`
  re-loads each id and drops nulls).
- `PUBLISH` with zero subscribers must not error; returns `0`.
- A write the backend will not take for its size → `ERR value too large for the backend`
  (nothing was written, and repeating it cannot help; Spring Session surfaces it as a failed
  save). Redis has no equivalent error — its own limit is 512 MB — so the message is ours.
- Keys/fields are UTF-8-decodable; values are opaque — never assume text.
- Back the store by a value-equal key wrapper, never raw `byte[]`.

## G. What is NOT needed

No `SCAN`/`KEYS`, no `MULTI`/`EXEC`, no Lua/`EVAL`, no `INFO`-driven features, no cluster
slots, no replication, no persistence. Spring Session's session path does not use them.
