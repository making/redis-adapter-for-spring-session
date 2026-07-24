I have all the source I need. Compiling the report.

---

# TASK C — Pub/Sub + Keyspace Notifications: what our `RedisConnection` must implement

All findings below are read from the actual sources. Citations are `file:line`. Paths are abbreviated:
- **SDR** = `…/scratchpad/sdr-sources/org/springframework/data/redis`
- **SS** = `/Users/toshiaki/git/spring-session/spring-session-data-redis/src/main/java/org/springframework/session/data/redis`

---

## 1. Where the pub/sub methods live (interface topology)

There is **no `pubSubCommands()` accessor** on the imperative connection. Unlike `geoCommands()`, `hashCommands()`, etc. (all declared in `RedisCommandsProvider`, SDR/connection/RedisCommandsProvider.java:24-121), pub/sub is **not** a segregated accessor. The five pub/sub methods are inherited *directly* onto `RedisConnection` through this chain:

- `RedisPubSubCommands` (SDR/connection/RedisPubSubCommands.java:30) declares the 5 methods.
- `RedisCommands extends … RedisPubSubCommands, …` (SDR/connection/RedisCommands.java:29-30).
- `DefaultedRedisConnection extends RedisCommands, RedisCommandsProvider` (SDR/connection/DefaultedRedisConnection.java:77) — and it provides **no default delegation** for any of the 5 pub/sub methods (grep for subscribe/pSubscribe/publish/getSubscription/isSubscribed in that file returns nothing).
- `RedisConnection extends RedisCommandsProvider, DefaultedRedisConnection, AutoCloseable` (SDR/connection/RedisConnection.java:49).

**Consequence for the adapter:** our concrete `RedisConnection` implementation must implement all five methods itself. There is nothing to delegate to, and no `pubSubCommands()` to override.

The exact signatures our connection must implement (from `RedisPubSubCommands`, SDR/connection/RedisPubSubCommands.java:37-80):

```java
boolean isSubscribed();                                       // line 37
@Nullable Subscription getSubscription();                     // line 45
Long publish(byte[] channel, byte[] message);                 // line 55
void subscribe(MessageListener listener, byte[]... channels); // line 67  (BLOCKING)
void pSubscribe(MessageListener listener, byte[]... patterns);// line 80  (BLOCKING)
```

Everything is `byte[]` — never `String`. Channels, patterns and message bodies are raw bytes end to end.

---

## 2. The value/callback types our adapter emits

### `Message` / `DefaultMessage` (SDR/connection/Message.java, DefaultMessage.java)
- `Message` is `Serializable` with two getters: `byte[] getBody()` (never null) and `byte[] getChannel()` (never null) — Message.java:33,40.
- We should deliver messages as `new DefaultMessage(channel, body)` (DefaultMessage.java:37-44). Constructor asserts both non-null. Note both `getChannel()` and `getBody()` return **defensive clones** (DefaultMessage.java:48,53) — so the container/listener can never mutate our internal arrays; we can pass our own arrays into the constructor safely.

### `MessageListener` (SDR/connection/MessageListener.java:29-37)
Functional interface, one method:
```java
void onMessage(Message message, byte @Nullable [] pattern);
```
- For a **channel** delivery, `pattern` must be `null`.
- For a **pattern** delivery, `pattern` must be the exact pattern bytes that matched (e.g. `spring:session:event:0:created:*`), and `message.getChannel()` must be the *actual* channel that the message was published to (e.g. `spring:session:event:0:created:<id>`). The container's dispatcher branches on this: pattern non-null/len>0 ⇒ look up `patternMapping`; else look up `channelMapping` keyed by `message.getChannel()` (RedisMessageListenerContainer.java:1179-1195).

### `SubscriptionListener` (SDR/connection/SubscriptionListener.java:27-66)
The `MessageListener` we are handed by the container **also implements `SubscriptionListener`** (it is a `SynchronizingMessageListener`, see §4). Four default no-op callbacks:
```java
onChannelSubscribed(byte[] channel, long count)     // line 40
onChannelUnsubscribed(byte[] channel, long count)   // line 48
onPatternSubscribed(byte[] pattern, long count)     // line 56
onPatternUnsubscribed(byte[] pattern, long count)   // line 64
```
**These are not optional decoration for us.** The container's startup handshake *blocks on them* (see §4.3). Our `subscribe`/`pSubscribe`/`Subscription.subscribe`/`Subscription.pSubscribe` implementations must invoke `onChannelSubscribed`/`onPatternSubscribed` (one call per channel/pattern) after registering, or the container throws `"Subscription registration timeout exceeded"` after 2s.

### `Subscription` (SDR/connection/Subscription.java:27-101)
`getSubscription()` must return an object implementing this. Methods our impl must honor:
- `subscribe(byte[]... channels)` / `pSubscribe(byte[]... patterns)` — add to the live subscription (throws `RedisInvalidSubscriptionException` if dead). Lines 34,41.
- `unsubscribe()` / `unsubscribe(byte[]...)` / `pUnsubscribe()` / `pUnsubscribe(byte[]...)` — lines 46-65.
- `Collection<byte[]> getChannels()` / `getPatterns()` — lines 72,79.
- `MessageListener getListener()` — line 86.
- `boolean isAlive()` — line 93.
- `void close()` — line 100. Called by the container to tear down (RedisMessageListenerContainer.java:1385-1392).

Contract note (Subscription.java:20-22): a subscription is **not** thread-safe for the *reader* but is expected to accept `subscribe`/`unsubscribe` mutations from another thread while the reader thread blocks — see §4.2 which relies on exactly this.

---

## 3. Which subscriber path the container uses — **BlockingSubscriber** (critical)

The container picks its internal subscriber via `ConnectionUtils.isAsync(connectionFactory)`:

```java
private Subscriber createSubscriber(RedisConnectionFactory cf, Executor ex) {
    return ConnectionUtils.isAsync(cf) ? new Subscriber(cf) : new BlockingSubscriber(cf, ex);
}
```
(RedisMessageListenerContainer.java:834-837)

And `isAsync` returns true **only** for `LettuceConnectionFactory` (SDR/connection/ConnectionUtils.java:29-31). Our custom `RedisConnectionFactory` is neither Lettuce nor Jedis ⇒ **`isAsync == false` ⇒ the container always uses `BlockingSubscriber`.**

This is the single most important design fact: the container treats our `subscribe()`/`pSubscribe()` as **blocking calls** (matching the Jedis model), run on a background executor thread, that do not return until the subscription is torn down.

---

## 4. How the container drives a connection (threading + call order + handshake + dispatch + lifecycle)

### 4.1 Obtaining the connection
- `afterPropertiesSet()` creates the subscriber and default `SimpleAsyncTaskExecutor` (RedisMessageListenerContainer.java:298-314, 324-327). If no `subscriptionExecutor` is set, it reuses the `taskExecutor` (line 308-310).
- On `start()` → `lazyListen()` → `doSubscribe()` → `Subscriber.initialize(...)` calls `connectionFactory.getConnection()` **once** and stores it (RedisMessageListenerContainer.java:1272-1274). One connection is multiplexed for all listeners.
- Immediately after acquiring it, the container asserts `!connection.isSubscribed()` (RedisMessageListenerContainer.java:1276-1282). So a **freshly returned connection must report `isSubscribed() == false`**, otherwise startup aborts with `IllegalStateException("Retrieved connection is already subscribed; aborting listening")`.

### 4.2 The subscribe/pSubscribe call sequence (BlockingSubscriber)
With Spring Session there are **both** channels (`del`, `expired`) **and** a pattern (`created:*`) on one container. `BlockingSubscriber.eventuallyPerformSubscription` (RedisMessageListenerContainer.java:1515-1551) handles the "both present" case specially:

1. It runs the subscribe work on the executor thread (line 1540).
2. `doSubscribe(connection, patterns, initiallySubscribeToChannels)` where, because both are non-empty, `initiallySubscribeToChannels` is **empty** (line 1520-1523). So the very first call is:
   ```java
   connection.pSubscribe(synchronizingMessageListener, patterns.toArray(new byte[0][]));
   ```
   (RedisMessageListenerContainer.java:1322-1325) — **this call blocks the executor thread** for the life of the subscription.
3. The channels are subscribed **later**, from a *different* thread, via a synchronization callback that fires once the pattern subscription is confirmed:
   ```java
   subscribeChannel(channels.toArray(new byte[0][]));  // → Subscription.subscribe(...)
   ```
   (RedisMessageListenerContainer.java:1525-1532, and `subscribeChannel` → `doWithSubscription` → `Subscription::subscribe`, lines 1426-1428, 1457-1472).

**Implication:** our blocking `pSubscribe` must (a) create the `Subscription`, register the patterns in the store's pub/sub registry, fire `onPatternSubscribed` for each pattern, publish `getSubscription()` so it is visible to other threads, and then park the calling thread until the subscription is closed. Meanwhile `Subscription.subscribe(channels)` will be invoked from another thread and must add channels to the *same* live subscription and fire `onChannelSubscribed`. (If instead only channels or only patterns existed, `doSubscribe` would call the plain blocking `subscribe`/`pSubscribe` directly — lines 1322-1335.)

### 4.3 The subscription-confirmation handshake — **mandatory**
The listener we receive is a `SynchronizingMessageListener` wrapping the real dispatcher (RedisMessageListenerContainer.java:1247-1248). It implements `SubscriptionListener`; each `onChannelSubscribed`/`onPatternSubscribed` decrements a per-registration "remaining" set, and when all channels+patterns are confirmed it runs the done-callback that completes the container's `initFuture` (SynchronizingMessageListener.java:66-83, 148-180). The container's `start()` blocks on that future up to `maxSubscriptionRegistrationWaitingTime` (default **2000 ms**, RedisMessageListenerContainer.java:121, 383) and throws `IllegalStateException("Subscription registration timeout exceeded")` on timeout (line 393-394).

⇒ **Our adapter MUST invoke `listener.onPatternSubscribed(pattern, count)` for every pattern and `onChannelSubscribed(channel, count)` for every channel, synchronously as part of (p)subscribe.** The `count` value is not inspected by Spring Session (it just needs the callback); any value (e.g. running subscriber count, or 1) is fine. `count` is a `long`.

Because the listener also is what receives messages, we should keep one reference: the `MessageListener` passed to `pSubscribe`/`subscribe` is the same object that must receive both subscription callbacks (cast to `SubscriptionListener`) and message deliveries.

### 4.4 Message dispatch & threading
- When our store publishes, we invoke `listener.onMessage(new DefaultMessage(channel, body), patternOrNull)` on the subscription's listener.
- Inside the container this reaches `DispatchMessageListener.onMessage` (RedisMessageListenerContainer.java:1176-1195), which resolves the matching listeners and then **hands off to the `taskExecutor`**: `dispatchMessage` does `executor.execute(() -> processMessage(listener, message, source))` (lines 1001-1009). So the actual `RedisIndexedSessionRepository.onMessage` runs on a container task-executor thread, *not* on our publishing thread. We do not need our own thread pool for delivery, but our `onMessage` invocation must be non-blocking/quick because it only enqueues.
- `source` passed to the app listener is `pattern.clone()` when pattern != null, else `message.getChannel()` (line 1003). Spring Session's `onMessage` ignores the `pattern` arg and reads `message.getChannel()`/`message.getBody()` (SS/RedisIndexedSessionRepository.java:572-573,589), so what matters is that **channel bytes and body bytes are correct**.

### 4.5 Lifecycle (start / stop / add / remove)
- `SmartLifecycle`, `autoStartup=true`, `phase=Integer.MAX_VALUE` (RedisMessageListenerContainer.java:111,173-174,637-639) ⇒ started automatically on context refresh, stopped first on shutdown.
- **stop/unsubscribe:** `stopListening()` → `Subscriber.unsubscribeAll()` → `BlockingSubscriber.doUnsubscribe` calls `closeSubscription(connection)` which calls `connection.getSubscription().close()` (RedisMessageListenerContainer.java:1341-1359, 1381-1397, 1510-1512). Our `Subscription.close()` must unblock the parked (p)subscribe thread and free the connection. After `close()` the blocking `doSubscribe` returns, then `closeConnection()`/`unsubscribeFuture.complete(null)` runs (lines 1543-1545).
- **runtime add:** `addMessageListener` while running calls `Subscription.subscribe`/`pSubscribe` on the live subscription (lines 706-733). **runtime remove:** `Subscription.unsubscribe`/`pUnsubscribe` (lines 796-799). For Spring Session's fixed configuration these are exercised only at startup, but a correct `Subscription` should support them.
- `connection.close()` is also called at shutdown (line 1413). And on connection failure the container calls `handleSubscriptionException` → `closeConnection()` and retries with backoff (lines 897-941) — only if the thrown exception is a `RedisConnectionFailureException`. Our normal path should not throw that.

---

## 5. Spring Session wiring: exact channels, patterns, and namespace math

### 5.1 Container bean (SS/config/annotation/web/http/RedisIndexedHttpSessionConfiguration.java:122-139)
```java
container.setConnectionFactory(getRedisConnectionFactory());
container.addMessageListener(sessionRepository, Arrays.asList(
        new ChannelTopic(sessionRepository.getSessionDeletedChannel()),   // __keyevent@0__:del
        new ChannelTopic(sessionRepository.getSessionExpiredChannel())));  // __keyevent@0__:expired
container.addMessageListener(sessionRepository, Collections.singletonList(
        new PatternTopic(sessionRepository.getSessionCreatedChannelPrefix() + "*"))); // spring:session:event:0:created:*
```
- `ChannelTopic` ⇒ `subscribe` (channel); `PatternTopic` ⇒ `pSubscribe` (pattern). The container distinguishes by `instanceof ChannelTopic`/`PatternTopic` (RedisMessageListenerContainer.java:692-701).
- The `RedisIndexedSessionRepository` **is** the `MessageListener` (SS/RedisIndexedSessionRepository.java:572 `onMessage`).

### 5.2 The exact strings (SS/RedisIndexedSessionRepository.java:472-479), with defaults `namespace = "spring:session:"`, `database = 0`:
```
sessionCreatedChannelPrefix = namespace + "event:" + database + ":created:"  → "spring:session:event:0:created:"
sessionDeletedChannel       = "__keyevent@" + database + "__:del"            → "__keyevent@0__:del"
sessionExpiredChannel       = "__keyevent@" + database + "__:expired"        → "__keyevent@0__:expired"
expiredKeyPrefix            = namespace + "sessions:expires:"                → "spring:session:sessions:expires:"
```
The `0` is the DB index (`DEFAULT_DATABASE = 0`, line 284; injected from the connection factory's `getDatabase()` in the config, lines 214-224). If a namespace or database is customized, all four strings shift accordingly — our adapter must not hard-code `0` or `spring:session`; it must publish channel names that mirror whatever key names it was given.

### 5.3 Bytes vs string
- Topic names are serialized to bytes by the container using **`StringRedisSerializer` (UTF-8 by default)** (RedisMessageListenerContainer.java:167, 1030-1033; SDR/serializer/StringRedisSerializer.java:25-26). So `subscribe`/`pSubscribe` receive UTF-8 bytes of the strings above.
- Spring Session builds its own comparison byte arrays with plain `String.getBytes()` (platform default charset) — e.g. `sessionDeletedChannelBytes` (line 475), `expiredKeyPrefixBytes` (line 479). For ASCII channel/key names UTF-8 == default charset, so this is consistent. Our published channel/body bytes should be the UTF-8 (== ASCII here) encoding of the names.

### 5.4 What `onMessage` does with each message (SS/RedisIndexedSessionRepository.java:572-623) — this defines exactly what bytes we must send:

**Created** (pattern `spring:session:event:0:created:*`):
- Detected by `messageChannel` startsWith `sessionCreatedChannelPrefixBytes` (line 575).
- `sessionId = channel.substring(channel.lastIndexOf(":")+1)` (line 577-578) ⇒ **channel must be `spring:session:event:0:created:<sessionId>`**.
- `entries = defaultSerializer.deserialize(message.getBody())` (line 580) ⇒ **body must be the serialized session `delta` map**.
- This message is **published by Spring Session itself**, not synthesized by us (see §6.2).

**Deleted / Expired** (channels `__keyevent@0__:del` / `__keyevent@0__:expired`):
- Guard: `messageBody` must startWith `expiredKeyPrefixBytes` = `spring:session:sessions:expires:`; otherwise the message is ignored (line 589-593). ⇒ **body = the dead key name.**
- `isDeleted = channel equals __keyevent@0__:del`; else must equal `__keyevent@0__:expired` (lines 595-596).
- `sessionId = body.substring(body.lastIndexOf(":")+1)` (line 597-600) ⇒ **body = `spring:session:sessions:expires:<sessionId>`.**
- Then it loads the session and fires `SessionDeletedEvent` / `SessionExpiredEvent` (lines 616-621).

### 5.5 The dead key (which key's death produces del/expired)
The "shadow key" carrying the TTL is `getSessionKey("expires:" + sessionId)` = `namespace + "sessions:" + "expires:" + sessionId` = **`spring:session:sessions:expires:<sessionId>`** (SS/RedisIndexedSessionRepository.java:941-943, 678-679). Its TTL is `maxInactiveInterval` seconds (createShadowKey, and boundValueOps.expire at 958-961). This is exactly the byte prefix `onMessage` filters on. So:
- When that shadow key **expires** → we must publish `__keyevent@0__:expired` with body = that key name.
- When that shadow key is **DEL**eted (e.g. by cleanup or session delete) → we must publish `__keyevent@0__:del` with body = that key name.

---

## 6. What our adapter must PUBLISH, and when

There are **two** distinct message flows. Only one of them is "keyspace notification" that we must synthesize; the other is an ordinary application PUBLISH we must merely route.

### 6.1 Keyspace notifications (`del` / `expired`) — **synthesized by our key-value store**
Real Redis emits these; our store has to fake them. Whenever a key `K` (any key — but the only one Spring Session cares about is the `…:sessions:expires:<id>` shadow key) leaves the store, publish:

| Trigger in the store | Channel (bytes, UTF-8) | Body (bytes) | When |
|---|---|---|---|
| Key deleted via `DEL`/`UNLINK` | `__keyevent@<db>__:del` | the deleted key's name | at delete time |
| Key removed because TTL reached 0 (passive on access, or active sweeper) | `__keyevent@<db>__:expired` | the expired key's name | when the key is actually evicted |

Notes:
- `<db>` is the database index the connection is on (0 by default). Channel format string is `"__keyevent@" + db + "__:del"` / `":expired"` — mirror it exactly.
- Body is the **key name only**, as raw bytes (the same bytes the key was stored under). No JSON, no prefix decoration.
- These are delivered to **channel** subscribers ⇒ call `listener.onMessage(new DefaultMessage(channelBytes, keyBytes), null)` (pattern = null).
- Timing subtlety Spring Session depends on (SS/RedisIndexedSessionRepository.java:195-261 doc): Redis does not guarantee prompt expired events, so Spring Session *also* actively deletes/touches expired shadow keys via a background cleanup + on-read. For our adapter the cleanup path issues `DEL` on the shadow key ⇒ that must fire the **`del`** event. A purely lazy TTL implementation that never notifies would break `SessionDeletedEvent`/`SessionExpiredEvent`. **Our store must emit the event synchronously at the point of deletion/expiry**, not merely drop the key.
- To satisfy the config check (§7) we should only emit expired/del events when they'd be observed by a subscriber; but emitting always (like Redis with `Egx` enabled) is simplest and correct.

### 6.2 The `created` event — **published by Spring Session via `PUBLISH`, we just route it**
On a new session save, Spring Session calls `sessionRedisOperations.convertAndSend(sessionCreatedKey, delta)` (SS/RedisIndexedSessionRepository.java:923-926), where `sessionCreatedKey = spring:session:event:0:created:<sessionId>`. `RedisTemplate.convertAndSend` obtains a *separate* (non-subscribed) connection from the factory and calls our **`publish(channelBytes, serializedDeltaBytes)`**. Our `publish` must:
1. Look up all subscriptions whose **channels** contain `channel` exactly, and whose **patterns** glob-match `channel`.
2. For each pattern match, deliver `onMessage(new DefaultMessage(channel, body), patternBytes)`.
3. Return the number of receivers as `Long` (see §8 — Spring Session ignores the value, but return a non-null count outside pipeline/txn).

This is the reason our pub/sub registry must be **shared across connections** (subscriber connection ≠ publisher connection). See §8.

Pattern matching required: at minimum Redis-glob `*` (the created pattern is `spring:session:event:0:created:*`). Supporting `?` and `[...]` is nice-to-have; `*` is mandatory.

---

## 7. `CONFIG SET` / `CONFIG GET notify-keyspace-events` (serverCommands) our connection must tolerate

Before subscribing, `EnableRedisKeyspaceNotificationsInitializer.afterPropertiesSet` runs `ConfigureNotifyKeyspaceEventsAction.configure(connection)` on a one-shot connection (SS/config/annotation/web/http/RedisIndexedHttpSessionConfiguration.java:141-144, 238-267). That action (SS/config/ConfigureNotifyKeyspaceEventsAction.java:53-85) does:

```java
Properties config = connection.serverCommands().getConfig("notify-keyspace-events");   // line 74
String notifyOptions = config.isEmpty() ? "" : config.getProperty(config.stringPropertyNames().iterator().next());
// ensure it contains 'E', and (unless 'A') 'g' and 'x'
if (!notifyOptions.equals(customized))
    connection.serverCommands().setConfig("notify-keyspace-events", customized);        // line 68
```

So our `serverCommands()` (from `RedisCommandsProvider.serverCommands()`, RedisCommandsProvider.java:96) must implement, from `RedisServerCommands` (SDR/connection/RedisServerCommands.java:172,181):

```java
Properties getConfig(String pattern);        // must NOT return null; return a Properties
void setConfig(String param, String value);  // must accept and not throw
```

Required behavior for our no-op-but-correct implementation:
- `getConfig("notify-keyspace-events")` → return a `java.util.Properties`. Simplest safe choices:
  - Return a single-entry Properties `{ "notify-keyspace-events" = <stored value, default "Egx"> }`. Spring Session reads the **first** property value (line 78). If we return the already-configured value, it will compare equal and *skip* `setConfig`.
  - Or return an **empty** Properties ⇒ Spring Session treats current as `""` and will call `setConfig(..., "Egx")`. Also fine, as long as `setConfig` is a tolerant no-op.
  - Do **not** throw `InvalidDataAccessApiUsageException` here — the action catches it and rethrows as a fatal `IllegalStateException` (SS/config/ConfigureNotifyKeyspaceEventsAction.java:80-84), which would abort startup.
- `setConfig("notify-keyspace-events", "...")` → store or ignore the value; must return normally. Since our store *always* emits del/expired events, the value is irrelevant to behavior — but the call must succeed.

(If a user instead wires `ConfigureRedisAction.NO_OP`, the initializer skips both calls entirely — line 252-254 — but we should still support the default action, which is `ConfigureNotifyKeyspaceEventsAction`, RedisIndexedHttpSessionConfiguration.java:76.)

Other `RedisServerCommands` methods (`info`, `shutdown`, `resetConfigStats`, etc.) are not on the Spring Session pub/sub path; only `getConfig`/`setConfig` are exercised here.

---

## 8. Concrete spec — "what each `RedisConnection` pub/sub method must do"

Design premise: a **process-wide pub/sub registry** owned by the store/connection-factory (not per-connection), because the subscriber and publisher are different connections. Suggested shape:
```
registry.channels: Map<ByteArrayKey, Set<SubscriptionHandle>>
registry.patterns: Map<ByteArrayKey, Set<SubscriptionHandle>>   // key = pattern bytes
SubscriptionHandle = { listener, ownedSubscription, alive }
```
A `SubscriptionHandle`/`Subscription` is per-subscribed-connection.

### `boolean isSubscribed()`
- Return `true` iff this connection currently owns a live `Subscription` with ≥1 channel or pattern.
- Must return `false` on a freshly obtained connection (container asserts this — §4.1).

### `Subscription getSubscription()`
- Return this connection's current `Subscription`, or `null` if not subscribed.
- Must become visible to *other threads* promptly after a blocking `subscribe`/`pSubscribe` begins (the container calls `getSubscription()` from a different thread to add channels — §4.2). Publish the reference (volatile/atomic) *before* parking the reader thread.

### `void subscribe(MessageListener listener, byte[]... channels)` — BLOCKING
1. If already subscribed, throw (real clients reject; container never does this to us though).
2. Create the `Subscription` (store `listener`, empty pattern set, the given channels).
3. Register each channel in `registry.channels`.
4. For each channel, call `((SubscriptionListener) listener).onChannelSubscribed(channel, count)` — **required for the handshake** (§4.3). Guard with `instanceof SubscriptionListener` (the container's listener always is).
5. Publish `getSubscription()`; set `isSubscribed()`→true.
6. **Block the calling thread** until the subscription is closed (`Subscription.close()`/`unsubscribe`-to-empty). Use a latch/condition; wake on close.
7. On wake, deregister from the registry and return.

### `void pSubscribe(MessageListener listener, byte[]... patterns)` — BLOCKING
- Same as `subscribe`, but register in `registry.patterns` and fire `onPatternSubscribed(pattern, count)` per pattern. This is the **first** call for Spring Session (patterns+channels case, §4.2); it parks the executor thread. Channels are added afterward through `Subscription.subscribe(...)` from another thread — so the `Subscription` returned here must accept concurrent `subscribe(channels)` and fire `onChannelSubscribed` at that time.

### `Long publish(byte[] channel, byte[] message)`
1. Build `Message msg = new DefaultMessage(channel, message)` (or reuse per delivery).
2. Deliver to exact channel subscribers: for each handle in `registry.channels[channel]`, call `handle.listener.onMessage(msg, null)`.
3. Deliver to matching pattern subscribers: for each pattern `P` where glob(P, channel), for each handle in `registry.patterns[P]`, call `handle.listener.onMessage(new DefaultMessage(channel, message), P)`.
4. Return the receiver count as `Long` (non-null when not pipelined/in-MULTI). Spring Session ignores the number, but a non-null `Long` is expected by callers; returning `0L`/actual count is fine. (`publish` may be called on a connection that is *not* itself subscribed — that is normal for `convertAndSend`.)

### Store-internal keyspace emitter (not a `RedisConnection` interface method, but required)
- On `DEL`/eviction of any key `K` on db `d`: `publishToRegistry("__keyevent@" + d + "__:del", K.bytes)` and `":expired"` for TTL expiry. This reuses the same `publish` routing so channel subscribers (`__keyevent@0__:del`/`:expired`) get `onMessage(msg, null)`. Emit **synchronously at the moment of removal**.

### `Subscription` implementation
- `subscribe/pSubscribe(byte[]...)`: add to live sets + registry + fire the matching `onChannelSubscribed`/`onPatternSubscribed`; throw `RedisInvalidSubscriptionException` if `!isAlive()`.
- `unsubscribe/pUnsubscribe(...)` and their no-arg forms: remove from registry, fire `onChannelUnsubscribed`/`onPatternUnsubscribed` (optional for Spring Session but correct); when both sets become empty, mark not-alive and wake the blocked reader.
- `getChannels()/getPatterns()`: live snapshots (`byte[]` collections).
- `getListener()`: the `MessageListener` from (p)subscribe.
- `isAlive()`: true until closed/emptied.
- `close()`: deregister everything, mark dead, **wake the parked (p)subscribe thread** (this is how `stop()` unblocks — §4.5).

---

## 9. Edge cases & gotchas to encode

1. **Freshly returned connection must be `isSubscribed() == false`** or startup aborts (RedisMessageListenerContainer.java:1276-1282).
2. **Fire `onPatternSubscribed`/`onChannelSubscribed` synchronously** during (p)subscribe, once per topic. Missing them ⇒ 2s hang then `"Subscription registration timeout exceeded"` (lines 383,393; SynchronizingMessageListener.java:171-177).
3. **`getSubscription()` must be cross-thread visible before blocking**, because channels are added from a different thread than the one blocked in `pSubscribe` (BlockingSubscriber, lines 1520-1532, 1457-1472).
4. **`subscribe`/`pSubscribe` genuinely block** for our factory (BlockingSubscriber path, because we are not `LettuceConnectionFactory` — ConnectionUtils.java:29-31). Run/park accordingly; don't return early.
5. **Message delivery must be async-friendly**: our `onMessage` call only enqueues onto the container's `taskExecutor` (lines 1001-1009), so keep the publish→onMessage handoff quick and don't hold store locks while the listener runs.
6. **Byte fidelity**: channel and body must be the exact UTF-8 bytes of the names Spring Session expects; `DefaultMessage` clones defensively (DefaultMessage.java:48,53) so passing internal arrays is safe.
7. **`del` vs `expired` selection is by channel equality** in `onMessage` (lines 595-596). Publishing an expired key on the `del` channel (or vice-versa) would misclassify the event. Emit on the correct channel per removal cause.
8. **Body guard**: del/expired messages whose body does *not* start with `spring:session:sessions:expires:` are silently dropped (lines 589-593). Emitting keyspace events for *all* keys is harmless (Spring Session filters), but we must emit for the shadow key specifically.
9. **`serverCommands().getConfig` must not return null and must not throw** `InvalidDataAccessApiUsageException`; `setConfig` must accept the call (§7).
10. **`close()`/`connection.close()`** on the subscription connection must unblock and release; the container calls `Subscription.close()` then `connection.close()` on stop and on connection-failure recovery (lines 1385-1416, 900).
11. **Pattern matcher must implement `*`** (Redis glob) to match `…created:*`. `getChannel()` on the delivered message must be the concrete channel, `pattern` arg the pattern bytes (dispatcher branches on this, lines 1179-1195).
12. The SDR listener package also contains `KeyspaceEventMessageListener`/`KeyExpirationEventMessageListener` — those belong to Spring Data Redis `@RedisHash` repositories and are **not** used by Spring Session (Spring Session registers `RedisIndexedSessionRepository` directly as the listener). No need to support them for this task.
