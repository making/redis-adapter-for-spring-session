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
007 (the indexed E2E harness), 004 (`SELECT` and the multi-database server). Independent of
008, though if 008 makes the number of databases configurable this test is the natural
consumer of that.

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
1. Let the test harness serve more than one database: either extend
   `AdapterServerTestConfiguration` to build a list of stores and pass
   `RedisAdapterServer.builder().databases(...)`, or add a sibling configuration for it.
   The `KeyValueStore` bean a test asserts on must then be the one for the database under
   test.
2. A raw-protocol test (core module, alongside the existing Lettuce integration tests) that
   subscribes to `__keyevent@1__:expired` and asserts a key expiring on database 1 arrives
   there and *not* on `__keyevent@0__:expired`. This is the assertion that names the
   failing layer; the Spring Session test below would only say "no event arrived".
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
- The Boot module currently declares a single `KeyValueStore` bean. Whether a deployed
  server can serve several databases is task 008's decision; this task only needs the test
  harness to serve two.
- Keep the assertion on the channel *name* rather than only on the Spring Session event: an
  event that fails to arrive is the symptom of a dozen possible faults, and the point of
  this task is to pin down one of them.
