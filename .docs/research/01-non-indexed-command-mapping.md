I have everything needed. Here is the report.

---

# TASK A — Non-Indexed Path Command Mapping (`RedisSessionRepository`)

Traced against actual sources. Line citations use the files:
- Session repo: `/Users/toshiaki/git/spring-session/spring-session-data-redis/src/main/java/org/springframework/session/data/redis/RedisSessionRepository.java`
- SDR core: `…/scratchpad/sdr-sources/org/springframework/data/redis/core/{RedisTemplate,DefaultHashOperations,AbstractOperations,RedisOperations}.java`
- SDR connection: `…/connection/{RedisKeyCommands,RedisHashCommands,RedisConnection,DefaultedRedisConnection}.java`

## 1. What `RedisSessionRepository` actually calls, and where

Only **6 distinct high-level ops** are used. Every one of them is issued against `RedisOperations<String, Object>` (`this.sessionRedisOperations`, injected `RedisTemplate`):

| # | High-level call | Site in RedisSessionRepository |
|---|---|---|
| 1 | `sessionRedisOperations.hasKey(key)` | `save()`, line 129 |
| 2 | `sessionRedisOperations.<String,Object>opsForHash().entries(key)` | `findById()`, line 140 |
| 3 | `sessionRedisOperations.delete(key)` | `deleteById()`, line 155 |
| 4 | `sessionRedisOperations.rename(originalSessionIdKey, sessionIdKey)` | `saveChangeSessionId()`, line 319 |
| 5 | `sessionRedisOperations.opsForHash().putAll(key, new HashMap<>(delta))` | `saveDelta()`, line 330 |
| 6 | `sessionRedisOperations.expireAt(key, Instant)` | `saveDelta()`, line 331 |

Key shape (line 166-167): `"spring:session:" + "sessions:" + sessionId`, e.g. `spring:session:sessions:<uuid>`. There is **no index maintenance, no ZSET, no SET, no pub/sub, no SCAN** on this path — that is the indexed repository's job.

**Ordering inside `save()`** (line 306-335): `saveChangeSessionId()` (→ RENAME, only if id changed and not new) runs **first**, then `saveDelta()` (→ HMSET then PEXPIREAT). `save()` in the repository is preceded (for non-new sessions) by the `hasKey` EXISTS check in the public `save(...)` (line 129).

## 2. Exact low-level `RedisConnection` command per op

### Dispatch model (important for the adapter)
- `RedisTemplate` key ops go through `doWithKeys(...)` which calls **`connection.keyCommands()`** and applies the lambda to the returned `RedisKeyCommands` (RedisTemplate.java:852-854: `execute((RedisCallback) connection -> action.apply(connection.keyCommands()), true)`).
- `DefaultHashOperations` calls the hash methods **directly on the `RedisConnection`** (e.g. `connection.hGetAll(...)`, `connection.hMSet(...)`).
- `RedisConnection extends … DefaultedRedisConnection` (RedisConnection.java:49). `DefaultedRedisConnection` implements the direct methods as **default methods that delegate to the sub-command interface** — e.g. `default Boolean exists(byte[] key){ return keyCommands().exists(key); }` (DefaultedRedisConnection.java:98-99), and hash methods delegate to `hashCommands()`. `RedisCommandsProvider` declares `RedisHashCommands hashCommands()` (line 48) and `RedisKeyCommands keyCommands()` (line 64).

  → **Adapter consequence:** implement `keyCommands()` and `hashCommands()` returning your own `RedisKeyCommands` / `RedisHashCommands`; the inherited default methods route both the direct-style and `xxxCommands()`-style calls to them. You do **not** need to implement each direct method twice.

### Command-by-command trace

| High-level op | Intermediate | Terminal `RedisConnection` command (byte[]) | Redis command |
|---|---|---|---|
| **`hasKey(K)`** | `RedisTemplate.hasKey` (RedisTemplate.java:589-594) → `doWithKeys(c -> c.exists(rawKey))` | `RedisKeyCommands.exists(byte[] key)` **default** (RedisKeyCommands.java:76-81) → `exists(byte[][])` (line 92); returns `count > 0` | `EXISTS` |
| **`opsForHash().entries(K)`** | `DefaultHashOperations.entries` (DefaultHashOperations.java:381-387) | `Map<byte[],byte[]> hGetAll(byte[] key)` (RedisHashCommands.java:174) | `HGETALL` |
| **`delete(K)`** | `RedisTemplate.delete(K)` (RedisTemplate.java:606-612) → `doWithKeys(c -> c.del(rawKey))` | `Long del(byte[]... keys)` (RedisKeyCommands.java:101); template maps `result==1 → Boolean` | `DEL` |
| **`rename(K,K)`** | `RedisTemplate.rename` (RedisTemplate.java:708-717) → `doWithKeys(c -> c.rename(rawOldKey, rawNewKey))` | `void rename(byte[] oldKey, byte[] newKey)` (RedisKeyCommands.java:193) | `RENAME` |
| **`opsForHash().putAll(K, Map)`** | `DefaultHashOperations.putAll` (DefaultHashOperations.java:158-176) | `void hMSet(byte[] key, Map<byte[],byte[]> hashes)` (RedisHashCommands.java:96) | `HMSET` |
| **`expireAt(K, Instant)`** | `RedisOperations.expireAt(K,Instant)` **default** (RedisOperations.java:413-418) does `Date.from(instant)` → `RedisTemplate.expireAt(K,Date)` (RedisTemplate.java:745-749) → `doWithKeys(c -> c.pExpireAt(rawKey, date.getTime()))` | `Boolean pExpireAt(byte[] key, long unixTimeInMillis)` **default** (RedisKeyCommands.java:391-393) → `pExpireAt(byte[], long, ExpirationOptions.Condition.ALWAYS)` (line 406) | `PEXPIREAT` |

Answers to the specific questions posed:
- **`putAll` → `hMSet`, not `hSet`.** Confirmed at DefaultHashOperations.java:173 (`connection.hMSet(rawKey, hashes)`), single HMSET with the whole batch.
- **`entries` → `hGetAll`.** Confirmed line 384.
- **`hasKey` → `keyCommands().exists`** (which routes to EXISTS), not a hash existence check. Line 593.
- **`delete` → `del`** (DEL), returns a count that the template collapses to `Boolean`. Line 610.
- **`expireAt` → `pExpireAt` (PEXPIREAT), not `expireAt`/EXPIREAT.** The `Instant` overload is a default that goes through `Date`, and `RedisTemplate.expireAt(K,Date)` always calls `connection.pExpireAt(rawKey, date.getTime())` — millisecond precision. Lines 745-748.
- **`rename` → `rename` (RENAME), not RENAMENX.** Line 714.

## 3. Connection-layer types (all byte-oriented)

| Command | Signature at the connection boundary |
|---|---|
| EXISTS | `Long exists(byte[]... keys)` (+ `default Boolean exists(byte[] key)`) |
| HGETALL | `Map<byte[], byte[]> hGetAll(byte[] key)` |
| DEL | `Long del(byte[]... keys)` |
| RENAME | `void rename(byte[] oldKey, byte[] newKey)` |
| HMSET | `void hMSet(byte[] key, Map<byte[], byte[]> hashes)` |
| PEXPIREAT | `Boolean pExpireAt(byte[] key, long unixTimeInMillis)` (+ `…, long, ExpirationOptions.Condition)`) |

Keys are `byte[]`, hash fields/values are `byte[]`, batch put is `Map<byte[], byte[]>` (a `LinkedHashMap`, so insertion order is preserved — DefaultHashOperations.java:166). Return values the adapter must produce: `Long` (EXISTS/DEL), `Map<byte[],byte[]>` (HGETALL, must be non-null; empty map for a missing key), `Boolean` (PEXPIREAT), `void` (HMSET/RENAME).

## 4. Serialization / byte-vs-string handling

Spring Session builds the template in `AbstractRedisHttpSessionConfiguration.createRedisTemplate()` (lines 156-167):
- `setKeySerializer(RedisSerializer.string())` → **StringRedisSerializer (UTF-8)**
- `setHashKeySerializer(RedisSerializer.string())` → **StringRedisSerializer (UTF-8)**
- value serializer & hash-value serializer are **not** set explicitly; in `RedisTemplate.afterPropertiesSet()` (RedisTemplate.java:139-162) any unset serializer falls back to `defaultSerializer`, which is `getDefaultRedisSerializer()` if provided, otherwise **`JdkSerializationRedisSerializer`** (Java serialization).

So at the wire/adapter level:
- **Redis key bytes** = UTF-8 of `spring:session:sessions:<id>` (via `rawKey`, AbstractOperations.java:174-183).
- **Hash field bytes** = UTF-8 of the field name (via `rawHashKey`, lines 231-237). Field names come from `RedisSessionMapper`: `"creationTime"`, `"lastAccessedTime"`, `"maxInactiveInterval"`, and per-attribute `"sessionAttr:" + name` (RedisSessionMapper.java:42-59).
- **Hash value bytes** = JDK-serialized object (via `rawHashValue`, lines 250-256). The delta values are `Long` epoch-millis (creationTime/lastAccessedTime), `Integer` seconds (maxInactiveInterval), and arbitrary attribute objects (RedisSessionRepository.java:213-220). The adapter stores these as **opaque byte[]** and never needs to interpret them.

Since `keySerializer`/`hashKeySerializer` are non-null, the `key instanceof byte[]` short-circuits in `rawKey`/`rawHashKey` are **not** taken — everything is serialized.

## 5. Null attribute value handling in `putAll` (the key edge case)

`removeAttribute(name)` → `setAttribute(name, null)` → `delta.put("sessionAttr:"+name, null)` (RedisSessionRepository.java:251-260). On the non-indexed path this null flows straight into `putAll`.

In `DefaultHashOperations.putAll` (lines 168-170) every value goes through `rawHashValue(entry.getValue())` **unconditionally** — there is no null-check, no branch to HDEL. `rawHashValue(null)` calls `hashValueSerializer().serialize(null)`, and `JdkSerializationRedisSerializer.serialize` returns **`SerializationUtils.EMPTY_ARRAY` (a `byte[0]`)** for null (JdkSerializationRedisSerializer.java:94-98).

**Result: a removed/nulled attribute becomes an HMSET field whose value is an empty byte array — NOT an HDEL and NOT a field removal.** The field stays in the hash with a zero-length value. On read, `HGETALL` returns that empty byte[], and `deserialize(empty)` returns `null` (JdkSerializationRedisSerializer.java:110-114), which `RedisSessionMapper` maps back to a null attribute value. So round-trips are consistent, but **the adapter must treat an empty `byte[0]` hash value as a legitimate stored value, not as "delete this field"**, and must preserve zero-length values through HGETALL.

(Contrast: the *indexed* `RedisIndexedSessionRepository` does its own null-scan and issues explicit HDEL. That is out of scope for this task but explains why the non-indexed path can leave empty-valued fields behind.)

## 6. Other edge cases the adapter must replicate

- **`putAll` on empty map is a no-op** — `DefaultHashOperations.putAll` returns before touching the connection when `m.isEmpty()` (lines 160-162), and `saveDelta()` itself returns early if `delta.isEmpty()` (RedisSessionRepository.java:326). So **HMSET is never issued with an empty map**.
- **`HGETALL` of a missing key must return an empty (non-null) map.** `findById` treats `entries.isEmpty()` as "not found" (line 141). Returning null map would still be tolerated by `deserializeHashMap` (returns null) but then `.isEmpty()` would NPE — so return an empty map.
- **`RENAME` is only issued for an existing, non-new session** (`!this.isNew`, line 316) whose id changed. Real Redis `RENAME` errors if the source key does not exist; the adapter should mirror that (throw), since Spring Session guarantees the source exists here. `RENAME` must overwrite the destination if it exists and must carry over the value **and** the TTL — though in practice `saveDelta()` immediately re-issues `PEXPIREAT` afterward, so TTL carryover is not strictly relied upon on this path.
- **`PEXPIREAT` uses absolute epoch-millis** = `lastAccessedTime.toEpochMilli() + maxInactiveInterval.getSeconds()` (RedisSessionRepository.java:331-333). It is issued **on every `saveDelta`**, i.e. after each HMSET. If `maxInactiveInterval` is negative (Spring Session's "never expire"), this yields a **past timestamp**, and real Redis `PEXPIREAT` in the past **deletes the key immediately** — a genuine quirk of the non-indexed path the adapter will reproduce if it faithfully emulates PEXPIREAT semantics. Condition is always `ALWAYS`.
- **`DEL` return value is discarded** by `deleteById` (it calls `delete(key)` for effect only), but `RedisTemplate.delete(K)` still reads the count to compute its `Boolean`, so the adapter's `del` must return a correct count (`0` or `1`).
- **`EXISTS` is checked on the *original* session-id key** when the id changed (`session.hasChangedSessionId() ? originalSessionId : getId()`, line 128), i.e. before the RENAME.

## 7. Minimal command set for the non-indexed adapter

To satisfy `@EnableRedisHttpSession` (non-indexed `RedisSessionRepository`) end-to-end, the adapter's `RedisConnection` needs exactly:

- `RedisHashCommands`: `hGetAll(byte[])`, `hMSet(byte[], Map<byte[],byte[]>)`
- `RedisKeyCommands`: `exists(byte[][])` (backs the `exists(byte[])` default), `del(byte[][])`, `rename(byte[], byte[])`, `pExpireAt(byte[], long, Condition)` (backs the `pExpireAt(byte[], long)` default)
- plus the `keyCommands()` / `hashCommands()` providers and connection lifecycle (`close`, etc.); all other `DefaultedRedisConnection` default methods can remain unimplemented/unsupported for this path.

Everything is `byte[]`-in / `byte[]`-out; values are opaque JDK-serialized blobs and empty `byte[0]` is a valid value (never interpret it as a delete). An in-memory `ConcurrentHashMap<ByteArrayKey, Map<ByteArrayKey, byte[]>>` plus a per-key expiry timestamp map is sufficient — remember to honor PEXPIREAT-in-the-past as immediate deletion and to lazily expire on read for the `hGetAll`/`exists` paths.
