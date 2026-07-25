# 021 — A write etcd is too small for deserves its own error

Read first: `.docs/design/etcd-performance.md`, section "Session size", and the command
layer's mapping of a store failure onto a RESP error.

## Why

Measured on 2026-07-25: a session attribute of up to about 1.5 MB is accepted, and above it
etcd refuses the write — `etcdserver: request is too large` at 1600 KB (its default
`--max-request-bytes`), and `grpc: received message larger than max` at 4000 KB (the
gateway's message limit). The adapter answers all of it as:

    ERR internal error

with etcd's reason in its own log. That is not wrong — the write did not happen, and the
client is right to be told it failed rather than told it succeeded — but "internal error" sends
whoever is holding the exception looking for a bug in the adapter, when the answer is that the
value does not fit in the cluster and no retry will change that.

It is a small thing, and it is the kind of small thing that costs somebody an afternoon.

## What to weigh

The mapping is in the core, not in the etcd backend, and it covers every backend, so this is
not a matter of special-casing a string from etcd in the command layer. Either:

- the SPI grows a way for a backend to say "this value is too big for me" — a dedicated
  exception, alongside `TypeMismatchException`, which the command layer maps to a message of
  its own; or
- the backend's exception carries something the command layer can already act on.

Redis has no equivalent error to copy (its own limit is 512 MB and it would have accepted
this), so the message is ours to choose. Something like `ERR value too large for the backend`
says what happened and that retrying will not help.

## Done when

- A write over the cluster's limit is answered with an error that names the reason, proved by
  a test at the wire level (the server module already drives a real client).
- The in-memory backend keeps behaving as it does: it has no such limit and must not grow a
  fake one.
- `README.md`'s etcd section says what the client sees, next to the size advice it already
  gives.
