# TASK D — Configuration Wiring & Serializers (Spring Session Data Redis 3.x / Spring Data Redis 4.1.0)

All findings below are read from the actual source files. File paths are absolute; line numbers cite the exact locations.

Key path abbreviations used below:
- `AbstractHttp` = `.../config/annotation/web/http/AbstractRedisHttpSessionConfiguration.java`
- `RedisHttp` = `.../config/annotation/web/http/RedisHttpSessionConfiguration.java`
- `RedisIndexedHttp` = `.../config/annotation/web/http/RedisIndexedHttpSessionConfiguration.java`
- `AbstractWebServer` = `.../config/annotation/web/server/AbstractRedisWebSessionConfiguration.java`
- `RedisWebServer` = `.../config/annotation/web/server/RedisWebSessionConfiguration.java`
- `SpringHttp` = `spring-session-core/.../config/annotation/web/http/SpringHttpSessionConfiguration.java`

---

## 1. Beans DECLARED vs. beans REQUIRED (autowired collaborators)

### 1a. `AbstractRedisHttpSessionConfiguration<T>` (base class — servlet/HTTP)

`@Configuration(proxyBeanMethods = false)` + `@Import(SpringHttpSessionConfiguration.class)` (`AbstractHttp:56-57`). It declares **no `@Bean` methods of its own** — it is purely a holder of configuration + collaborators + the `createRedisTemplate()` factory helper. Subclasses declare the actual `sessionRepository` bean.

Collaborators it pulls from the application context (setter injection):

| Collaborator | Injection point | Required? | Notes |
|---|---|---|---|
| `RedisConnectionFactory` | `setRedisConnectionFactory(...)` `AbstractHttp:119-125` | **YES (required)** | See §1e — this is the one bean the user MUST provide. |
| `RedisSerializer<Object>` (value serializer) | `setDefaultRedisSerializer(...)` `AbstractHttp:131-135` | optional (`required = false`) | Qualifier `@Qualifier("springSessionDefaultRedisSerializer")`. If absent, RedisTemplate falls back to JDK serialization (see §2). |
| `SessionRepositoryCustomizer<T>` (0..N) | `setSessionRepositoryCustomizer(...)` `AbstractHttp:141-145` | optional | Collected via `ObjectProvider.orderedStream()`. |
| `ClassLoader` | `setBeanClassLoader(...)` `AbstractHttp:151-154` | supplied by container (`BeanClassLoaderAware`) | Passed to the RedisTemplate. |

### 1b. `RedisHttpSessionConfiguration extends AbstractRedisHttpSessionConfiguration<RedisSessionRepository>`

`@Configuration(proxyBeanMethods = false)`, `implements EmbeddedValueResolverAware, ImportAware` (`RedisHttp:52-54`).

Beans DECLARED:
- **`sessionRepository()` → `RedisSessionRepository`** (`RedisHttp:60-75`, `@Bean` + `@Override`). Built from `createRedisTemplate()` (line 63), then configured with maxInactiveInterval, namespace, flushMode, saveMode, sessionIdGenerator, and all customizers.

Additional optional collaborator introduced here:
- `SessionIdGenerator` via `setSessionIdGenerator(...)` `RedisHttp:100-103` (`@Autowired(required = false)`; defaults to `UuidSessionIdGenerator.getInstance()` at `RedisHttp:58`).

Config attributes are read from the `@EnableRedisHttpSession` annotation via `setImportMetadata(...)` (`RedisHttp:82-98`): `maxInactiveIntervalInSeconds`, `redisNamespace`, `flushMode`, `saveMode`.

### 1c. `RedisIndexedHttpSessionConfiguration extends AbstractRedisHttpSessionConfiguration<RedisIndexedSessionRepository>`

`@Configuration(proxyBeanMethods = false)`, `implements EmbeddedValueResolverAware, ImportAware` (`RedisIndexedHttp:69-72`).

Beans DECLARED (three `@Bean` methods):
1. **`sessionRepository()` → `RedisIndexedSessionRepository`** (`RedisIndexedHttp:92-120`). Built from `createRedisTemplate()` (line 95). Additionally wired with: `applicationEventPublisher` (line 97), optional `indexResolver` (98-100), optional default serializer (101-103), maxInactiveInterval, namespace, flushMode, saveMode, **`cleanupCron`** (line 110), **`database`** (resolved at 111-112 via `resolveDatabase()`), sessionIdGenerator, optional `expirationStore`, and customizers.
2. **`springSessionRedisMessageListenerContainer(RedisIndexedSessionRepository)` → `RedisMessageListenerContainer`** (`RedisIndexedHttp:122-139`). See §3.
3. **`enableRedisKeyspaceNotificationsInitializer()` → `InitializingBean`** (`RedisIndexedHttp:141-144`). See §3.

Additional collaborators introduced here (all setter-injected):

| Collaborator | Injection point | Required? |
|---|---|---|
| `ApplicationEventPublisher` | `setApplicationEventPublisher(...)` `RedisIndexedHttp:160-163` | **required** (`@Autowired`, no `required=false`) — but the container always provides this, so no user action needed. |
| `ConfigureRedisAction` | `setConfigureRedisAction(...)` `RedisIndexedHttp:155-158` | optional; default `new ConfigureNotifyKeyspaceEventsAction()` (`RedisIndexedHttp:76`). |
| `IndexResolver<Session>` | `setIndexResolver(...)` `RedisIndexedHttp:165-168` | optional |
| `Executor` (task) | `setRedisTaskExecutor(...)` `RedisIndexedHttp:170-174`, `@Qualifier("springSessionRedisTaskExecutor")` | optional |
| `Executor` (subscription) | `setRedisSubscriptionExecutor(...)` `RedisIndexedHttp:176-180`, `@Qualifier("springSessionRedisSubscriptionExecutor")` | optional |
| `RedisSessionExpirationStore` | `setExpirationStore(...)` `RedisIndexedHttp:182-185` | optional |
| `SessionIdGenerator` | `setSessionIdGenerator(...)` `RedisIndexedHttp:226-229` | optional |

Config read from `@EnableRedisIndexedHttpSession` (`RedisIndexedHttp:192-212`): same four attributes plus `cleanupCron`.

**Edge case for the adapter — `resolveDatabase()` (`RedisIndexedHttp:214-224`):** it inspects the connection factory type. It only extracts a non-default DB index if the factory `instanceof LettuceConnectionFactory` (and `io.lettuce.core.RedisClient` present) or `instanceof JedisConnectionFactory` (and `redis.clients.jedis.Jedis` present). **Our custom `RedisConnectionFactory` is neither**, so it always falls through to `RedisIndexedSessionRepository.DEFAULT_DATABASE` (line 223). That is fine — the adapter does not need to satisfy any database-selection contract.

### 1d. `AbstractRedisWebSessionConfiguration<T>` (reactive/WebFlux) + `RedisWebSessionConfiguration`

`@Configuration(proxyBeanMethods = false)` + `@Import(SpringWebSessionConfiguration.class)` (`AbstractWebServer:45-46`). The abstract class declares **no `@Bean`**; the concrete `RedisWebSessionConfiguration` declares:
- **`sessionRepository()` → `ReactiveRedisSessionRepository`** (`RedisWebServer:87-100`), built from `createReactiveRedisTemplate()`.

Collaborators (reactive analogues):

| Collaborator | Injection point | Required? |
|---|---|---|
| `ReactiveRedisConnectionFactory` | `setRedisConnectionFactory(...)` `AbstractWebServer:99-109` / `RedisWebServer:119-129` | **YES (required)** — note it is `ReactiveRedisConnectionFactory`, NOT the servlet `RedisConnectionFactory`. |
| `RedisSerializer<Object>` | `setDefaultRedisSerializer(...)` `AbstractWebServer:111-115`, `@Qualifier("springSessionDefaultRedisSerializer")` | optional; **default is `new JdkSerializationRedisSerializer()`** (`AbstractWebServer:57`). |
| `ReactiveSessionRepositoryCustomizer<T>` | `setSessionRepositoryCustomizer(...)` `AbstractWebServer:117-121` | optional |
| `SessionIdGenerator` | `setSessionIdGenerator(...)` `AbstractWebServer:143-146` | optional |

Note: `SpringWebSessionConfiguration` (imported) supplies the `webSessionManager` / adapter beans for the reactive stack. The reactive stack is a **separate SPI** — a `ReactiveRedisConnectionFactory` — and is out of scope if the adapter only targets `@EnableRedisHttpSession` / `@EnableRedisIndexedHttpSession` (both servlet/blocking). Flagged here so the architect knows: supporting WebFlux would require implementing the reactive factory/connection interfaces too, which the current task does not require.

### 1e. Confirmation: `RedisConnectionFactory` is an autowired REQUIRED collaborator, NOT created here

`AbstractHttp:119-125`:
```java
@Autowired
public void setRedisConnectionFactory(
        @SpringSessionRedisConnectionFactory ObjectProvider<RedisConnectionFactory> springSessionRedisConnectionFactory,
        ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
    this.redisConnectionFactory = springSessionRedisConnectionFactory
        .getIfAvailable(redisConnectionFactory::getObject);
}
```

- The setter is `@Autowired` **without `required=false`**, so the method itself is a required injection point.
- Resolution logic: it first tries any factory qualified with `@SpringSessionRedisConnectionFactory` (`springSessionRedisConnectionFactory.getIfAvailable(...)`); if none is so-qualified, the fallback lambda `redisConnectionFactory::getObject` is invoked.
- `ObjectProvider.getObject()` throws `NoSuchBeanDefinitionException` if **zero** `RedisConnectionFactory` beans exist, and `NoUniqueBeanDefinitionException` if **multiple** exist and none carries the `@SpringSessionRedisConnectionFactory` qualifier.
- **Conclusion:** the config never constructs a factory. It requires exactly one `RedisConnectionFactory` bean in the context (or one disambiguated by the qualifier). `@SpringSessionRedisConnectionFactory` (`.../config/annotation/SpringSessionRedisConnectionFactory.java:36-42`) is a `@Qualifier` meta-annotation the user can put on their bean to disambiguate when other `RedisConnectionFactory` beans coexist.

---

## 2. How the `RedisTemplate<String,Object>` is built + default serializers

Built by `createRedisTemplate()` in the base class, `AbstractHttp:156-167`:
```java
protected RedisTemplate<String, Object> createRedisTemplate() {
    RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
    redisTemplate.setKeySerializer(RedisSerializer.string());       // line 158
    redisTemplate.setHashKeySerializer(RedisSerializer.string());   // line 159
    if (getDefaultRedisSerializer() != null) {                      // line 160
        redisTemplate.setDefaultSerializer(getDefaultRedisSerializer());
    }
    redisTemplate.setConnectionFactory(getRedisConnectionFactory());
    redisTemplate.setBeanClassLoader(this.classLoader);
    redisTemplate.afterPropertiesSet();
    return redisTemplate;
}
```

Serializer matrix (servlet/HTTP path):

| Slot | Serializer | Evidence |
|---|---|---|
| **Key serializer** | `StringRedisSerializer` (UTF-8) | `AbstractHttp:158` calls `RedisSerializer.string()`, which returns `StringRedisSerializer.UTF_8` (`.../serializer/RedisSerializer.java:75-77`). |
| **Hash-key serializer** | `StringRedisSerializer` (UTF-8) | `AbstractHttp:159`, same factory. |
| **Value serializer** | If a `springSessionDefaultRedisSerializer` bean is provided → that bean; **otherwise `JdkSerializationRedisSerializer`** | `setDefaultSerializer` only called when non-null (`AbstractHttp:160-162`). When left null, `RedisTemplate.afterPropertiesSet()` defaults it: `defaultSerializer = new JdkSerializationRedisSerializer(classLoader...)` (`RedisTemplate.java:143-147`), then propagates it to value + hashValue slots (`RedisTemplate.java:149-163`). |
| **Hash-value serializer** | Same as value serializer (default JDK, or the provided default) | `RedisTemplate.java:160-162` — hashValueSerializer falls back to `defaultSerializer`. |

So the default matches the task's expectation: **`StringRedisSerializer` for keys and hash-keys, `JdkSerializationRedisSerializer` for value and hash-value.** (Note: key/hashKey are explicitly `RedisSerializer.string()` and are NOT overridden by the default serializer; only value + hashValue get the default.)

**Byte-vs-string implications for the adapter (critical):**
- **Keys and hash-fields** arrive at `RedisConnection` as UTF-8-encoded `byte[]` of a human-readable String (e.g. `spring:session:sessions:<uuid>`, `spring:session:sessions:expires:<uuid>`, hash-fields like `sessionAttr:<name>`, `creationTime`, `lastAccessedTime`, `maxInactiveInterval`, `sessionId`). The adapter can decode these with `new String(bytes, StandardCharsets.UTF_8)` for internal keying/logging.
- **Values and hash-values** arrive as **opaque JDK-serialized `byte[]`** (unless the app installs a JSON `springSessionDefaultRedisSerializer`). The adapter must treat them as **opaque binary blobs** — store and return the exact bytes, never assume UTF-8, never mutate them. Equality/round-trip fidelity is all that matters; the adapter does not (and must not) deserialize them.
- All `RedisConnection` command args/returns are `byte[]`. The adapter must key its backing map by a value-equal wrapper (e.g. `ByteBuffer.wrap` or a `String` decode of the key), because raw `byte[]` does not implement `equals`/`hashCode` — a naive `ConcurrentHashMap<byte[],…>` will never find anything back.

The **reactive** path builds a `ReactiveRedisTemplate` via `RedisSerializationContext` instead (`AbstractWebServer:127-137`, `RedisWebServer:170-180`): key + hashKey = `RedisSerializer.string()`; default (value + hashValue) = the injected serializer or `new JdkSerializationRedisSerializer()`. Same serializer semantics, different template type.

---

## 3. Indexed-config wiring: listener container, initializer, ConfigureRedisAction

### `RedisMessageListenerContainer` bean — `springSessionRedisMessageListenerContainer` (`RedisIndexedHttp:122-139`)
```java
@Bean
public RedisMessageListenerContainer springSessionRedisMessageListenerContainer(
        RedisIndexedSessionRepository sessionRepository) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(getRedisConnectionFactory());        // line 126
    if (this.redisTaskExecutor != null) { container.setTaskExecutor(...); }
    if (this.redisSubscriptionExecutor != null) { container.setSubscriptionExecutor(...); }
    container.addMessageListener(sessionRepository,
        Arrays.asList(new ChannelTopic(sessionRepository.getSessionDeletedChannel()),
                      new ChannelTopic(sessionRepository.getSessionExpiredChannel())));   // 133-135
    container.addMessageListener(sessionRepository,
        Collections.singletonList(new PatternTopic(sessionRepository.getSessionCreatedChannelPrefix() + "*"))); // 136-137
    return container;
}
```
- It calls `getRedisConnectionFactory()` (the same user-supplied factory) at line 126.
- It registers the `RedisIndexedSessionRepository` itself as a `MessageListener` on two `ChannelTopic`s (session-deleted, session-expired) and one `PatternTopic` (`...created:*`).
- **Adapter implication:** in indexed mode, `RedisMessageListenerContainer` will, on startup, open a connection and call **`subscribe` / `pSubscribe`** (and expect keyspace-notification / pub-sub messages to be delivered). To make `SessionDeletedEvent` / `SessionExpiredEvent` fire, the adapter must either implement pub/sub + keyspace-notification emission on key expiry/deletion, or the application must accept that these events won't fire. This is the single biggest additional surface required by `@EnableRedisIndexedHttpSession` vs. `@EnableRedisHttpSession`. The channel names come from `sessionRepository.getSessionDeletedChannel()`, `getSessionExpiredChannel()`, `getSessionCreatedChannelPrefix()`.

### `InitializingBean enableRedisKeyspaceNotificationsInitializer()` (`RedisIndexedHttp:141-144`)
Returns a `EnableRedisKeyspaceNotificationsInitializer` (static inner class, `RedisIndexedHttp:238-269`) constructed with `(getRedisConnectionFactory(), this.configureRedisAction)`. On `afterPropertiesSet()` (`RedisIndexedHttp:250-267`):
```java
if (this.configure == ConfigureRedisAction.NO_OP) { return; }      // line 252
RedisConnection connection = this.connectionFactory.getConnection(); // line 255
try { this.configure.configure(connection); }                        // line 257
finally { connection.close(); }
```

### `ConfigureRedisAction` (default `ConfigureNotifyKeyspaceEventsAction`, `RedisIndexedHttp:76`)
Injected optionally via `setConfigureRedisAction` (`RedisIndexedHttp:155-158`). The default action's `configure(RedisConnection)` (`.../config/ConfigureNotifyKeyspaceEventsAction.java`) executes, against the connection:
- `connection.serverCommands().getConfig("notify-keyspace-events")` → Redis **`CONFIG GET notify-keyspace-events`**
- if the returned flags lack `E`/`g`/`x`, `connection.serverCommands().setConfig("notify-keyspace-events", "<flags>")` → Redis **`CONFIG SET notify-keyspace-events Egx`** (or `Ex`, etc.)

**Adapter implication (critical):** with the default action, indexed mode will, at startup, call `RedisConnection.serverCommands().getConfig(...)` and possibly `setConfig(...)`. The adapter's `serverCommands()` must at minimum tolerate these two calls (return an empty/benign `Properties` from `getConfig`, accept `setConfig` as a no-op) — otherwise startup fails. The clean escape hatch built into Spring Session: **expose a `ConfigureRedisAction.NO_OP` bean**, which short-circuits at `RedisIndexedHttp:252` and never touches the connection. Documentation for the adapter should recommend `@Bean ConfigureRedisAction configureRedisAction() { return ConfigureRedisAction.NO_OP; }` for indexed mode to avoid needing CONFIG support.

---

## 4. EXACT minimal beans the user application must define

**For `@EnableRedisHttpSession` (non-indexed, servlet):**
- **Exactly one bean:** a `org.springframework.data.redis.connection.RedisConnectionFactory` — supplied by our adapter library (backed by the pluggable KV store / default `ConcurrentHashMap`). That's it. (`AbstractHttp:119-125` is the only required collaborator the user must satisfy.)

**For `@EnableRedisIndexedHttpSession` (indexed, servlet):**
- The same **one required bean**: our adapter's `RedisConnectionFactory`.
- **Strongly recommended second bean:** `@Bean ConfigureRedisAction` returning `ConfigureRedisAction.NO_OP`, unless the adapter's `serverCommands().getConfig/setConfig` are implemented as tolerant no-ops. Without one or the other, `enableRedisKeyspaceNotificationsInitializer` fails at startup (`RedisIndexedHttp:255-257`).
- For `SessionDeleted`/`SessionExpired`/`SessionCreated` events to actually fire, the adapter must additionally support pub/sub + keyspace notifications (see §3). This is a functional requirement of the adapter, not a bean the user defines.

If the adapter registers its factory bean under a non-`RedisConnectionFactory`-unique name while other Redis factories exist, the user should annotate the adapter's `@Bean` with `@SpringSessionRedisConnectionFactory` to disambiguate (`AbstractHttp:121`).

Everything else is optional (`SessionIdGenerator`, `IndexResolver`, `RedisSerializer` named `springSessionDefaultRedisSerializer`, the two `Executor`s, `SessionRepositoryCustomizer`s, `RedisSessionExpirationStore`, `CookieSerializer`, `HttpSessionIdResolver`) — all `@Autowired(required = false)` with sane defaults.

---

## 5. Everything Spring Session AUTO-PROVIDES (user defines none of these)

From `AbstractRedisHttpSessionConfiguration` + subclass:
- `sessionRepository` → `RedisSessionRepository` (`RedisHttp:60`) OR `RedisIndexedSessionRepository` (`RedisIndexedHttp:92`).
- The internal `RedisTemplate<String,Object>` (not a bean — created inline by `createRedisTemplate()`, `AbstractHttp:156`), fully wired with the user's connection factory and the String/JDK serializers.

Indexed-only extras:
- `springSessionRedisMessageListenerContainer` → `RedisMessageListenerContainer` (`RedisIndexedHttp:122`).
- `enableRedisKeyspaceNotificationsInitializer` → `InitializingBean` (`RedisIndexedHttp:141`).

From the imported `SpringHttpSessionConfiguration` (`@Import` at `AbstractHttp:57`):
- `springSessionRepositoryFilter` → `SessionRepositoryFilter<? extends Session>` (`SpringHttp:119-125`) — the servlet filter that wraps `HttpServletRequest`/`HttpSession`.
- `sessionEventHttpSessionListenerAdapter` → `SessionEventHttpSessionListenerAdapter` (`SpringHttp:114-117`).
- Default `CookieHttpSessionIdResolver` + `DefaultCookieSerializer` (created internally, `SpringHttp:97`, `170-199`) unless overridden.

Reactive (`RedisWebSessionConfiguration` + imported `SpringWebSessionConfiguration`) analogously auto-provides `sessionRepository` (`ReactiveRedisSessionRepository`) and the `webSessionManager` adapter beans — but this path needs a `ReactiveRedisConnectionFactory`, a distinct SPI outside the current servlet-focused task.

---

## 6. Summary for the architect

- The adapter's sole hard contract for both servlet annotations is a single **`RedisConnectionFactory`** bean. Everything else (the `RedisTemplate`, session repository, servlet filter, listeners) is auto-declared by Spring Session; the factory is an `@Autowired` required collaborator (`AbstractHttp:119-125`), never constructed inside the config.
- The adapter's `RedisConnection` sees: **UTF-8 String bytes** for keys and hash-fields (safe to decode), **opaque JDK-serialized bytes** for values/hash-values (must be stored/returned byte-for-byte, never interpreted). Back the store with a value-equal key wrapper, not raw `byte[]`.
- Non-indexed mode (`@EnableRedisHttpSession`) needs only the standard data commands (the ones exercised by `RedisSessionRepository` over `RedisTemplate`); no CONFIG, no pub/sub, no server commands.
- Indexed mode (`@EnableRedisIndexedHttpSession`) additionally drives `serverCommands().getConfig/setConfig("notify-keyspace-events")` at startup (via `ConfigureNotifyKeyspaceEventsAction`) and `subscribe`/`pSubscribe` (via `RedisMessageListenerContainer`). To keep the adapter minimal, either implement those as tolerant no-ops/real pub-sub, or document that the app must expose `ConfigureRedisAction.NO_OP`; and note that keyspace-expiry events require the adapter to emit notifications for the destroy/expire events to fire.
