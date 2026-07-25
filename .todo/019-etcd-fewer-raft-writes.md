# 019 — Cut the raft writes a session save costs

Read first: `.docs/design/etcd-performance.md` (the measurement this comes out of),
`.docs/design/architecture.md` §11.2 (leases), §11.6 (what it costs) and §11.7, and
`EtcdKeyValueStore.expireAt`.

## Why

Measured on 2026-07-25: one Spring Session save costs **12 raft writes**, and **9 of them
are the three `PEXPIREAT`s** Spring Session issues per save (on the session, its shadow key
and the expirations bucket). `PEXPIREAT` on a key that already has a TTL is:

    Range 1, LeaseGrant 1, Txn 1, LeaseRevoke 1

— three of those four are raft writes, because the key is moved onto a freshly granted lease
and the old lease is then revoked. Since a session write is bounded by the cluster's raft
write rate (`sessions/s ≈ writes/s ÷ 12`), removing two of the three writes per `PEXPIREAT`
is close to a factor of two in session throughput, and about two thirds of a save's latency.

The case is the ordinary one, not a corner: Spring Session pushes the *same* TTL out on every
request, because `maxInactiveInterval` does not change from request to request.

## What to build

`LeaseKeepAlive` renews a lease for the TTL it was granted with, and does not cost a raft
write per renewal. So when `expireAt` is asked for a deadline whose lease TTL (whole seconds,
rounded up — `leaseSeconds`) equals the TTL the key's current lease was granted with, keep
that lease alive instead of granting a new one and revoking the old:

    Range 1, LeaseKeepAlive 1, Txn 1     (one raft write, for the value's own deadline)

Two things make this less mechanical than it looks:

- **The granted TTL has to be known.** A store did not necessarily grant the lease it finds
  on a key — another replica may have — so it cannot be remembered in a map and assumed.
  Either ask etcd (`/v3/lease/timetolive`, which is a read but another round trip) or carry
  the granted TTL in the envelope beside `expireAtMillis`, which is a value-format change and
  needs the format version bumped and the old format still read.
- **The deadline still has to be written.** The exact expiry is the millisecond deadline in
  the value, and that is what every read honours, so the `Txn` stays. What goes away is the
  grant and the revoke.

Worth measuring at the same time, since the harness now exists: whether `PEXPIREAT` with a
deadline in the same second as the current one can be answered without writing anything at
all, and what Spring Session's actual sequence does with that.

## Done when

- `PEXPIREAT` on a key whose lease already has the right TTL costs one raft write, proved by
  counting etcd calls (`EtcdMetrics` in the server module's performance harness does this).
- A lease is still never left holding nothing: the keepalive path must not leak leases, and
  the grant-and-revoke path stays for a TTL that really changed.
- Expiry behaviour is unchanged under the existing suites, including
  `EtcdKeyValueStoreTest`'s lease-driven expiry and the end-to-end `SessionExpiredEvent`.
- `.docs/design/etcd-performance.md` is re-run and says what it bought; §11.7 loses the item.
