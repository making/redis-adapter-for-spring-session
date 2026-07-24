# 013 — Non-default namespace and database end-to-end

## Context
Every end-to-end test written so far runs on the defaults: namespace `spring:session:` and
database 0. Both research reports single this out as the thing the adapter must not
hard-code (research 03 §5.2: "our adapter must not hard-code `0` or `spring:session`; it
must publish channel names that mirror whatever key names it was given"), and the design
does parameterize it — `KeyspaceNotifier` takes a database index and `RedisAdapterServer`
registers one notifier per database — but nothing proves it. **An adapter that hard-coded
`__keyevent@0__:` would pass the entire current suite.** The keyspace channel is also the
one place where a wrong name fails silently: no error is raised, the event simply never
arrives, and the application sees sessions that are never reported as expired.

## Depends on
007 (the indexed E2E harness), 004 (`SELECT` and the multi-database server).

## Ordering — do this in two parts, around 008
This task straddles 008, so **split it rather than doing it in one sitting**:

- **Part 1 (before 008)** — the raw-protocol test in the core module, step 1 below. It
  needs no Spring Boot and no configuration, it is the part that actually catches the
  silent failure, and there is nothing to gain by waiting for 008.
- **Part 2 (after 008)** — the harness and the Spring Session E2E, steps 2-4 below. 008
  already owns the `databases` count as a configuration property (see its step 1, which
  binds `RedisAdapterServer.Builder.databases(List<KeyValueStore>)`), so writing the
  multi-database test harness first would mean building a `@TestConfiguration` that 008
  then makes redundant. Build part 2 on 008's real property instead.

Keeping the two apart also keeps failure attribution sharp: with part 1 already green, a
failure in part 2 is the new Boot wiring rather than the adapter's keyspace handling.

## Goal
An automated E2E test where a stock Spring Session application configured with a custom
namespace **and** `spring.data.redis.database=1` observes exactly the same created /
deleted / expired events and principal-index lookups as it does on the defaults.

## Key references
- `.docs/research/03-pubsub-keyspace-notifications.md` §5.2 — the four strings Spring
  Session derives from namespace + database (created-channel prefix, del channel, expired
  channel, expired-key prefix), and §6.1 for the `__keyevent@<db>__:` format.
- `.docs/research/02-indexed-command-mapping.md` §2 — every key name is namespace-derived.
- `RedisIndexedHttpSessionConfiguration.resolveDatabase()` — the database index Spring
  Session builds its channel names from is read off the Lettuce connection factory, so
  `spring.data.redis.database=1` is what drives it. The namespace comes from the annotation
  attribute `@EnableRedisIndexedHttpSession(redisNamespace = "...")`.
- Adapter side: `RedisAdapterServer` constructor (the per-database `KeyspaceNotifier`
  loop), `KeyspaceNotifier`, `ConnectionCommands.select`.

## What must work
1. **Keys land in the right keyspace**: with the client on database 1, every session key is
   written to the second store and the database-0 store stays empty.
2. **Channel names carry the right index**: removing a key on database 1 publishes
   `__keyevent@1__:del` / `__keyevent@1__:expired`, never `@0`.
3. **Pub/sub is server-wide, not per database**: a subscriber is reached regardless of
   which database its connection selected, which is how Redis behaves and what the
   server-wide `PubSubRegistry` already implements. Worth an explicit assertion, because
   the obvious "fix" if something looks wrong here is to make the registry per database,
   which would be incorrect.
4. **A custom namespace shifts all four strings together**: keys, the created-event
   channel, and the expired-key prefix Spring Session filters incoming messages on.
5. `SELECT` with an index the server does not serve still errors, and the existing
   single-database default is unaffected.

## Steps / deliverables

### Part 1 — before 008 (done)
Delivered as `RedisAdapterServerMultiDatabaseKeyspaceTest` in the core module. It found no
fault: the per-database `KeyspaceNotifier` already published the right channel names, so
there was nothing to fix and no regression test to add. That the test would have caught a
fault was checked by hard-coding `__keyevent@0__:` in `KeyspaceNotifier.channel` and
watching the two database-1 tests go red on the channel name.

1. A raw-protocol test (core module, alongside the existing Lettuce integration tests) that
   subscribes to `__keyevent@1__:expired` and asserts a key expiring on database 1 arrives
   there and *not* on `__keyevent@0__:expired`, plus the `SELECT` routing that puts the key
   in the second store to begin with. This is the assertion that names the failing layer;
   the Spring Session test below would only say "no event arrived". Fix whatever it
   surfaces, with a regression test for each.

### Part 2 — after 008 (008 is done; this is what it left you)
- `redis-adapter.databases=2` is the property that makes the server serve database 1; set
  it with `@SpringBootTest(properties = ...)` on the new test class.
- `AdapterServerTestConfiguration` now imports the shipped configuration
  (`KeyValueStoreConfiguration` + `RedisAdapterServerConfiguration`), so there is nothing
  to build: the harness already serves whatever `databases` says.
- The backends are one `KeyValueStores` bean, not one `KeyValueStore` bean per store.
  A test asserts on `databases.database(1)`, and `databases.database(0)` is the one that
  must stay empty.

2. Let the test harness serve more than one database, through 008's `databases`
   configuration property rather than a bespoke `@TestConfiguration`. The `KeyValueStore`
   a test asserts on must then be the one for the database under test.
3. An E2E test in the server module: `@EnableRedisIndexedHttpSession(redisNamespace = ...)`
   plus `spring.data.redis.database=1`, asserting created / deleted / expired events and
   `findByIndexNameAndIndexValue`, with the key names checked against the custom namespace
   through the store bean.
4. Fix whatever this surfaces, with a regression test for each.

## Acceptance criteria
- The E2E test passes on a non-default namespace and database 1, asserting the same
  behaviours 007 asserts on the defaults.
- The database-0 store is empty at the end of it, proving the keys really went elsewhere.
- The whole existing suite stays green.

## Notes / gotchas
- Spring Session reads the database index off the *connection factory*, not from a property
  of its own, so setting `spring.data.redis.database` is what makes the client and Spring
  Session's channel names agree. Setting one without the other is itself a case worth a
  thought: they cannot disagree through this configuration path.
- The adapter's `CONFIG` handling is database-independent and needs nothing here.
- Whether a deployed server can serve several databases was task 008's decision — which is
  why part 2 waits for it. It decided yes: `redis-adapter.databases` creates one backend
  per database. Part 1 needed none of that: it drives the core server directly, as the
  existing core integration tests do.
- Keep the assertion on the channel *name* rather than only on the Spring Session event: an
  event that fails to arrive is the symptom of a dozen possible faults, and the point of
  this task is to pin down one of them.
