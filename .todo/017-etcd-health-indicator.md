# 017 — Health indicator for a backend that can be unreachable

Read first: `.docs/design/architecture.md` (§8 actuator, §11 the etcd backend), and
`README.md`'s "Health and metrics" section, which is what this task makes true.

## Why

`README.md` says of `/actuator/health`:

> Whether the *backend* is reachable is not asked here: a backend that can be unreachable
> contributes a health indicator of its own.

With the in-memory backend that sentence cost nothing — a map is always reachable. The etcd
backend (task done 2026-07-25) is the first backend that can be down while the adapter is
up, and nothing contributes that indicator yet. Today an adapter whose etcd is unreachable
reports `UP`, keeps accepting connections, and answers every session command with
`ERR internal error`. A load balancer has no way to take it out.

`am.ik.redis.adapter.etcd.EtcdKeyValueStore.checkHealth()` already exists for this: it reads
through the store's own prefix, so it exercises the same round trip, endpoint failover and
credentials that real work does, and throws `EtcdException` when nothing answers.

## What to build

A health contributor in the server module (`am.ik.redis.adapter.boot`) that asks whichever
backends can be asked, and reports the adapter's store as `DOWN` with the reason when they
cannot.

## The constraint that makes this less obvious than it looks

**A property an operator sets at deploy time must never decide which beans exist** (see
`CLAUDE.md`): Spring evaluates `@Conditional` while an ahead-of-time image is built, so
`@ConditionalOnProperty("redis-adapter.backend")` would freeze the choice into the image.
The bean therefore has to exist for every backend and decide at runtime what it can say. Two
shapes to weigh:

- **One indicator over `KeyValueStores`**, which asks each store that can be asked and
  reports `UP` when none can be (an in-memory backend contributes nothing to check). The
  "can be asked" test is the awkward part: an `instanceof EtcdKeyValueStore` in the boot
  module is honest but does not generalize to a third backend.
- **A method on the SPI** — something like `default void checkHealth() {}` on
  `KeyValueStore`, which a backend overrides when it has something to check. This
  generalizes, and it is a change to the one interface every future backend implements, so
  it needs care: it must not become a way for the health endpoint to do real work, and the
  javadoc has to say it may be called often and must be cheap.

Prefer the second if the SPI can carry it without growing a second purpose; the first is the
fallback that changes nothing outside the server module.

## Done when

- An adapter configured for an etcd that is not there reports `/actuator/health` as `DOWN`,
  with the endpoints in the detail and no credentials anywhere in it.
- An adapter with a reachable etcd reports `UP`, and one on the in-memory backend still
  reports `UP` without an extra "unknown" contributor.
- The indicator does not fail the application's start-up, and a health check while etcd is
  briefly away recovers on its own.
- Covered by a test that starts the server against an endpoint that does not answer (no
  container needed for the `DOWN` case) and one against the Testcontainers etcd for the `UP`
  case.
- `README.md`'s "Health and metrics" section says what is now true, and the property table
  keeps matching what the server binds (`ReadmeExamplesTests`).
