I have read all the relevant source. Here is the complete research report.

---

# TASK B — Indexed Path Command Mapping (RedisIndexedSessionRepository)

All findings are from actually-read source. Primary files:
- `/Users/toshiaki/git/spring-session/spring-session-data-redis/src/main/java/org/springframework/session/data/redis/RedisIndexedSessionRepository.java`
- `.../RedisSessionExpirationStore.java`, `.../SortedSetRedisSessionExpirationStore.java`, `.../RedisSessionExpirationPolicy.java`
- `.../config/annotation/web/http/AbstractRedisHttpSessionConfiguration.java`, `.../RedisIndexedHttpSessionConfiguration.java`, `.../config/ConfigureNotifyKeyspaceEventsAction.java`
- Spring Data Redis `core/Default*Operations.java`, `BoundOperationsProxyFactory.java`, `AbstractOperations.java`, `RedisTemplate.java`

## 0. How Bound*Ops resolve to low-level RedisConnection commands

`RedisTemplate.boundHashOps/boundSetOps/boundValueOps` return AOP proxies built by `BoundOperationsProxyFactory.createProxy` (`BoundOperationsProxyFactory.java:61`). The interceptor (`:135-152`) routes each call:

- If the invoked method is **declared in the specific bound interface** (`BoundHashOperations`, `BoundSetOperations`, `BoundValueOperations`) → forwarded to the matching `Default*Operations` **with the bound key prepended** as arg 0.
- If the method is **declared in the super-interface `BoundKeyOperations`** (`expire(long,TimeUnit)`, `persist()`, `rename()`, `getExpire()`, `expireAt()`) → forwarded to `DefaultBoundKeyOperations` (`:193-255`), which calls back into `RedisOperations` (`ops.expire(key,…)`, `ops.persist(key)`, `ops.rename(key,newKey)`, `ops.hasKey(key)`).

Net effect: `boundHashOps(k).expire(...)`, `boundSetOps(k).expire(...)`, `boundValueOps(k).expire(...)`, and `.persist()` are all **key-level** operations (they hit `RedisTemplate.expire/persist`), NOT hash-field TTL. `append/putAll/entries/members/add/remove` hit the type-specific Default*Operations.

## 1. Master op → RedisConnection method table

Legend: call-site line = `RedisIndexedSessionRepository.java` unless noted. "Conn method" = the actual `org.springframework.data.redis.connection.RedisConnection` method invoked (verified in the Default*Operations / RedisTemplate source).

| # | Call site (file:line) | RedisOperations call | Default*Ops (file:line) | RedisConnection method | Redis command | Notes |
|---|---|---|---|---|---|---|
| 1 | saveDelta `:904` | `boundHashOps(sessionKey).putAll(delta)` | `DefaultHashOperations.putAll:172-175` | `hMSet(rawKey, Map<byte[],byte[]>)` | HMSET (skipped if delta empty, `:160-162`) | field names = String bytes; values = JDK bytes |
| 2 | getSession `:532` | `boundHashOps(sessionKey).entries()` | `DefaultHashOperations.entries:384` | `hGetAll(rawKey)` → `Map<byte[],byte[]>` | HGETALL | empty map ⇒ session treated absent (`:533`) |
| 3 | saveDelta `:934-935`; MinuteStore `:1041` | `boundHashOps(sessionKey).expire(sec,SECONDS)` / `boundSetOps(expirationsKey).expire(sec,SECONDS)` | `DefaultBoundKeyOperations.expire:218-220` → `RedisTemplate.expire:735-742` | **`pExpire(rawKey, millis)`** | **PEXPIRE** (not EXPIRE!) | `TimeoutUtils.toMillis(sec,SECONDS)`; ms precision |
| 4 | createShadowKey `:948,960`; policy analog | `boundValueOps(shadowKey).append("")` | `DefaultValueOperations.append:129-138` | `append(rawKey, rawString)` | APPEND | value = `stringSerializer.serialize("")` = **empty byte[0]** |
| 5 | createShadowKey `:949`; `:950-951` | `boundValueOps(shadowKey).persist()` / `boundHashOps(sessionKey).persist()` | `DefaultBoundKeyOperations.persist:233-235` → `RedisTemplate.persist:760-765` | `persist(rawKey)` | PERSIST | only on negative maxInactive (never-expire) |
| 6 | createShadowKey `:961` | `boundValueOps(shadowKey).expire(sec,SECONDS)` | `DefaultBoundKeyOperations.expire:218-220` → `RedisTemplate.expire:735-742` | **`pExpire(rawKey, millis)`** | PEXPIRE | shadow key TTL = maxInactive (no +5min) |
| 7 | deleteById `:556`; createShadowKey `:955`; MinuteStore `:1065` | `sessionRedisOperations.delete(key)` | `RedisTemplate.delete:606-612` | `del(rawKey)` → Long | DEL | **fires `del` keyevent** (see §3) |
| 8 | saveDelta `:919-920`; saveChangeSessionId `:992-993`; MinuteStore `:1044-1045` | `boundSetOps(setKey).add(member)` | `DefaultSetOperations.add:44-50` | `sAdd(rawKey, byte[][])` → Long | SADD | member = session-id or `expires:{id}` String bytes |
| 9 | cleanupPrincipalIndex `:630`; saveDelta `:911-912`; saveChangeSessionId `:990-991`; MinuteStore `:1034,1055` | `boundSetOps(setKey).remove(member)` | `DefaultSetOperations.remove:250-255` | `sRem(rawKey, byte[][])` → Long | SREM | |
| 10 | findByIndexNameAndIndexValue `:510`; MinuteStore `:1064` | `boundSetOps(setKey).members()` | `DefaultSetOperations.members:196-202` | `sMembers(rawKey)` → `Set<byte[]>` | SMEMBERS | null ⇒ empty map (`:511-513`) |
| 11 | saveDelta `:925` | `sessionRedisOperations.convertAndSend(channel, delta)` | `RedisTemplate.convertAndSend:1012-1021` | `publish(rawChannel, rawMessage)` → Long | PUBLISH | channel=String bytes; body=**JDK-serialized Map** |
| 12 | saveChangeSessionId `:974-975,983` | `sessionRedisOperations.rename(old,new)` | `RedisTemplate.rename:707-717` | `rename(rawOld, rawNew)` | RENAME | **must throw "ERR no such key" if old absent** (see §5) |
| 13 | touch `:1081`; SortedSet `:106` | `sessionRedisOperations.hasKey(key)` | `RedisTemplate.hasKey:588-594` | `exists(rawKey)` → Boolean | EXISTS | access-to-force-lazy-expiry; **must fire `expired` on elapsed TTL** |

Additional (not in the hot path but present in the codebase for the ZSet strategy) — see §6.

Notes worth flagging for the adapter:
- **Everything TTL-related uses `pExpire` (PEXPIRE, milliseconds)**, because both `RedisTemplate.expire(K,long,TimeUnit)` (`:737-742`) and `expire(K,Expiration)` (`:728-733`) delegate to `connection.pExpire`. There is no plain `EXPIRE` on this path.
- **`delete` uses `del` (DEL), not `unlink`** (`RedisTemplate.delete:610`).
- `putAll` maps to the legacy `hMSet` (not per-field `hSet`); a no-op short-circuit exists when `delta` is empty (`DefaultHashOperations.putAll:160`), so an empty save issues no HMSET.

## 2. Exact set of Redis keys & data types

Namespace default = `spring:session:` (`RedisIndexedSessionRepository.java:289,296`). `configureSessionChannels()` (`:471-480`) derives the channel/prefix strings; `database` default 0 (`:284`).

| Logical key | Format (default ns, db=0) | Type | Built by | Members/fields | TTL |
|---|---|---|---|---|---|
| **Session hash** | `spring:session:sessions:{id}` | HASH | `getSessionKey:678-680` | fields `creationTime`, `lastAccessedTime`, `maxInactiveInterval`, `sessionAttr:{name}` (`RedisSessionMapper.java:42-59`); field=UTF-8 String, value=JDK bytes | PEXPIRE = (maxInactive+5min)·1000 (`:933-935`) |
| **Shadow "expires" string** | `spring:session:sessions:expires:{id}` | STRING (empty) | `getExpiredKey:691-693` = `expiredKeyPrefix + id`; also `getSessionKey("expires:"+id)` (`createShadowKey:942-943`) — identical string | empty value | PEXPIRE = maxInactive·1000 (`:961`); or PERSIST if maxInactive<0 |
| **Principal index Set** | `spring:session:index:org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME:{principal}` | SET | `getPrincipalKey:682-685` | session-id Strings | **none — never expires** (only SREM on delete/expire/principal-change) |
| **Expirations bucket Set** (minute strategy) | `spring:session:expirations:{roundedEpochMillis}` | SET | `getExpirationsKey:687-689` | `expires:{id}` Strings | PEXPIRE = (maxInactive+5min)·1000 (`:1041`); DEL on cleanup (`:1065`) |
| **Created channel** (pub/sub) | `spring:session:event:0:created:{id}` | pub/sub | `sessionCreatedChannelPrefix` (`:472`) + id (`:695-697`) | PUBLISH body = JDK-serialized delta Map | — |
| **Deleted keyevent** (subscribe) | `__keyevent@0__:del` | pub/sub | `:474` | body = expired key name | — |
| **Expired keyevent** (subscribe) | `__keyevent@0__:expired` | pub/sub | `:476` | body = expired key name | — |

Rounding for the bucket key: `roundUpToNextMinute` (`:1092-1096`) = `Instant(expiryMs).plus(1 MINUTE).truncatedTo(MINUTES)`; cleanup reads the **previous** minute via `roundDownMinute(now)` (`:1098-1101`).

The `{roundedEpochMillis}` and hash field values are appended to keys/values as their `toString()`/serialized forms; note the class-level javadoc `HMSET … creationTime 1404360000000` (`:108,126`) is **illustrative only** — on the wire values are JDK-serialized blobs, not ASCII decimals (see §4). Likewise javadoc `spring:session:channel:created:` (`:163`) is stale; the real prefix is `event:{db}:created:` (`:472`).

## 3. Operations that MUST trigger keyspace notifications

`RedisIndexedHttpSessionConfiguration` registers listeners (`:122-139`):
- `ChannelTopic("__keyevent@0__:del")`, `ChannelTopic("__keyevent@0__:expired")`
- `PatternTopic("spring:session:event:0:created:*")`

`ConfigureNotifyKeyspaceEventsAction.configure` (`ConfigureNotifyKeyspaceEventsAction.java:53-70`) forces `notify-keyspace-events` to contain **`E`** (keyevent), **`g`** (generic cmds incl. DEL/RENAME/EXPIRE), **`x`** (expired). So the adapter must emit `__keyevent@0__:...` events. Required events:

1. **DEL of the shadow "expires" key ⇒ `__keyevent@0__:del`**, body = the shadow key name. This is the *only* thing that produces a `SessionDeletedEvent`. `deleteById` (`:546-560`) does not publish the event itself — it deletes the shadow key (`:556`) and relies on the `del` keyevent → `onMessage` (`:595-621`) → `handleDeleted` → `SessionDeletedEvent`. Setting maxInactive=0 then saving also re-DELs the shadow via `createShadowKey(0)` (`:954-956`).
2. **TTL expiry of the shadow "expires" key ⇒ `__keyevent@0__:expired`**, body = shadow key name → `handleExpired` → `SessionExpiredEvent`. Because Redis does not guarantee timely expiry, the background cleanup **EXISTS-touches** the key to force lazy expiry (`touch:1080-1082`; `SortedSet:105-107`). ⇒ **In the adapter, EXISTS (and ideally every access) on a key whose TTL has elapsed must (a) remove the key and (b) emit the `expired` keyevent** — passive expiration must be modeled, not just background sweeping.
3. **PUBLISH to the created channel** (`convertAndSend:925`) must deliver to pattern subscribers `spring:session:event:0:created:*` ⇒ `handleCreated` → `SessionCreatedEvent`.

Filtering nuance (`onMessage:589-596`): the listener only reacts when the **message body** begins with `expiredKeyPrefixBytes` = `spring:session:sessions:expires:` (`:478-479`). Therefore:
- Only **shadow-key** del/expired events actually matter. Events for the session hash (`spring:session:sessions:{id}`), principal set, or expirations bucket are received but filtered out (they don't contain `expires:`). A minimal adapter can emit del/expired for shadow keys only; emitting for all keys is harmless (they're filtered).
- The `del`/`expired` distinction is by **channel** (`:595-596`), the session id by parsing after the last `:` of the body (`:597-600`).

## 4. `APPEND ""` on a missing key + byte-vs-string handling

**`APPEND key ""` semantics** (redis-native, mirrored by the adapter requirement): if the key does not exist, APPEND **creates it as an empty string** (value length 0) and returns 0; if it exists, it is unchanged and its current length is returned. Spring Session exploits exactly this (`createShadowKey:941-963`, `RedisSessionExpirationPolicy:91-112`): the shadow key carries no data — it exists **only** to (a) hold a TTL and (b) generate `expired`/`del` keyevents. Sequence per save:
```
APPEND spring:session:sessions:expires:{id} ""     # create empty key if absent (conn.append, value = byte[0])
PEXPIRE spring:session:sessions:expires:{id} maxInactive*1000
```
Adapter requirement: `append(key, emptyBytes)` on an absent key must materialize a zero-length STRING key that subsequently reports `exists==true` and accepts PEXPIRE/PERSIST/DEL/RENAME. The empty value comes from `AbstractOperations.rawString("")` → `stringSerializer.serialize("")` (`AbstractOperations.java:186-187`), and `StringRedisSerializer` returns `new byte[0]` for `""`.

**Serializer configuration** (`AbstractRedisHttpSessionConfiguration.createRedisTemplate:156-167`):
- `keySerializer = RedisSerializer.string()` (StringRedisSerializer / UTF-8)
- `hashKeySerializer = RedisSerializer.string()` (UTF-8)
- `valueSerializer = hashValueSerializer = defaultSerializer` → `JdkSerializationRedisSerializer` unless a `springSessionDefaultRedisSerializer` bean overrides it (`RedisTemplate.afterPropertiesSet:143-162`)
- `stringSerializer` field is always `RedisSerializer.string()` (`RedisTemplate.java:117`), used by `rawString` (APPEND value, PUBLISH channel) and `convertAndSend`'s channel.

Byte-vs-string consequences the adapter must honor (all rawing done in `AbstractOperations` `:174-256`):

| Wire element | Serializer | On-the-wire bytes |
|---|---|---|
| Every key (`rawKey`) | String/UTF-8 | human-readable UTF-8 key names |
| Hash field names | String/UTF-8 | `creationTime`, `sessionAttr:foo`, … |
| **Hash field values** | JDK | **opaque serialized blobs** — adapter must store/return bytes verbatim, never parse them |
| Set members (session-id / `expires:{id}`) | JDK `valueSerializer` (`rawValue`, `DefaultSetOperations`) | ⚠ **JDK-serialized**, not plain UTF-8 — a raw String goes through `JdkSerializationRedisSerializer` |
| APPEND value (`""`) | String/UTF-8 | empty `byte[0]` |
| PUBLISH channel | String/UTF-8 | `spring:session:event:0:created:{id}` |
| PUBLISH body (delta map) | JDK `valueSerializer` | serialized `HashMap`; `onMessage` re-reads via `defaultSerializer` (`:580`) |
| ZSet member (SortedSet store) | JDK `valueSerializer` | session-id JDK-serialized; score = double |

Key point: the adapter is a **pure opaque byte-key/byte-value store**; it must never assume values are text. Only key names and hash-field names are UTF-8. (The javadoc examples showing plaintext values are misleading.)

## 5. Edge cases the adapter must get right

- **RENAME of a missing key** (`saveChangeSessionId:965-998`): when the session id changes, both the hash key and the shadow key are RENAMEd. Spring Session wraps each in try/catch and swallows only errors whose most-specific-cause message **starts with `"ERR no such key"`** (`handleErrNoSuchKeyError:1000-1005`); any other error is rethrown. ⇒ The adapter's `rename` must, when the source key is absent, throw an exception whose message begins exactly with `ERR no such key` (Spring Data maps the raw Redis error into a `NonTransientDataAccessException`). Renaming a nonexistent key must **not** silently succeed.
- **Principal set has no TTL** — it is only shrunk via SREM on delete/expire/principal change (`:630, 911-913, 990-991`). A crash between hash-expiry and cleanup can leak dangling ids; `findByIndexNameAndIndexValue` tolerates this by re-`findById`-ing each id and dropping nulls (`:514-521`).
- **`getSession` gates on empty hash** (`:532-534`): HGETALL returning empty ⇒ null session. So DEL/expiry of the hash is observable as "session gone" even without a keyevent.
- **`del` on already-absent shadow key** produces no event; `deleteById` may DEL the shadow twice (once at `:556`, once via `createShadowKey(0)` at `:955`) — the adapter should emit the `del` keyevent only when the key actually existed, and be idempotent for the second DEL.
- **Minute-bucket cleanup touches the SHADOW key**: entries stored are `expires:{id}` (`:1027,1054`), and cleanup calls `touch(getSessionKey(entry))` = `getSessionKey("expires:{id}")` = `spring:session:sessions:expires:{id}` (`:1069-1071`). So the touched key is exactly the shadow key whose expiry produces the matching event.
- **`convertAndSend` publishes even with zero subscribers** — must not error; returns receiver count (Long).
- **`notify-keyspace-events` config**: `ConfigureNotifyKeyspaceEventsAction` calls `connection.serverCommands().getConfig(...)` / `setConfig(...)`. The adapter must either implement these `serverCommands` config getters/setters as tolerant no-ops, or the user must supply `ConfigureRedisAction.NO_OP` (`RedisIndexedHttpSessionConfiguration.java:76,155-158`). This is a startup-only call.

## 6. ZSet-based alternative — `SortedSetRedisSessionExpirationStore`

A single global sorted set replaces the per-minute buckets. Configured via `setExpirationStore` (`RedisIndexedSessionRepository.java:668-671`). Key = `{namespace}:sessions:expirations` (`SortedSetRedisSessionExpirationStore.java:120`); session key touched = `{namespace}:sessions:{id}` (`:109-111`).

| Store op (file:line) | RedisOperations call | Default*Ops (file:line) | RedisConnection method | Redis command |
|---|---|---|---|---|
| `save:63-66` | `opsForZSet().add(key, id, expiryMillis)` | `DefaultZSetOperations.add:58-65` | `zAdd(rawKey, double score, byte[] value)` → Boolean | ZADD |
| `remove:72-75` | `opsForZSet().remove(key, sessionId)` | `DefaultZSetOperations.remove:377-384` | `zRem(rawKey, byte[][])` → Long | ZREM |
| `cleanupExpiredSessions:83-93` | `opsForZSet().reverseRangeByScore(key, 0, now, 0, cleanupCount)` | `DefaultZSetOperations.reverseRangeByScore:306-313` | `zRevRangeByScore(rawKey, min, max, offset, count)` → `Set<byte[]>` | ZREVRANGEBYSCORE key max min LIMIT offset count |
| `touch:105-107` | `redisOps.hasKey(sessionKey)` | `RedisTemplate.hasKey:588-594` | `exists(rawKey)` | EXISTS |

Details:
- **Member** = session id (JDK-serialized via `valueSerializer`, per `DefaultZSetOperations.add` → `rawValue`); **score** = `getLastAccessedTime()+getMaxInactiveInterval()` epoch-millis as a `double` (`:64,95-97`).
- **Query for expired**: `reverseRangeByScore(key, min=0, max=clock.millis(), offset=0, count=cleanupCount)` — the 5-arg overload (`ZSetOperations.java:359`). Adapter's `zRevRangeByScore` must return members whose score ∈ [0, now], highest-score-first, capped at `count` (default 100, `:45`). No ZREM is issued — entries are pruned later by `remove()` on delete/expiry.
- **Behavioral difference to note**: the minute store touches the **shadow** key (produces `expires:`-prefixed `expired` events that Spring Session acts on); the sorted-set store touches the **session hash** key (`{ns}:sessions:{id}`), whose expired keyevent body does NOT match the `spring:session:sessions:expires:` filter. With the ZSet strategy the `SessionExpiredEvent` is driven by the shadow key expiring on its own TTL (still created by `createShadowKey`); the touch merely forces lazy cleanup of the hash. The adapter still only needs shadow-key expired/del events for correctness.

Minimal RedisConnection surface for a **ZSet alternative**: add `zAdd(key,score,value)`, `zRem(key,values...)`, `zRevRangeByScore(key,min,max,offset,count)` to the core set below.

## 7. Minimal RedisConnection command surface for the indexed path

String/hash/set/key/pubsub commands the adapter must implement (all byte-oriented):
`hMSet`, `hGetAll`, `sAdd`, `sRem`, `sMembers`, `append`, `del` (single + varargs), `rename` (with "ERR no such key" on missing source), `exists` (single + varargs), `pExpire`, `persist`, `publish`; plus pub/sub subscribe/pSubscribe delivery for `__keyevent@0__:del`, `__keyevent@0__:expired`, and `spring:session:event:0:created:*`; plus tolerant `serverCommands().getConfig/setConfig("notify-keyspace-events")` (or require `ConfigureRedisAction.NO_OP`). For the ZSet strategy add `zAdd`, `zRem`, `zRevRangeByScore`. The store engine must model **passive TTL expiration that emits `expired` keyevents on access** and **`del` keyevents on delete**, since `SessionExpiredEvent`/`SessionDeletedEvent` depend entirely on those notifications rather than on direct calls.
