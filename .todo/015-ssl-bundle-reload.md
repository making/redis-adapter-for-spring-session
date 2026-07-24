# 015 — Reload the server certificate without a restart (optional)

## Context
Task 010 terminates TLS from a Spring Boot `SslBundle`, but it builds the `SSLContext`
once, as `SslBundleServerSocketFactory` is created. Certificate material replaced on disk —
which is how cert-manager, Vault and friends rotate it — therefore only reaches clients
when the adapter is restarted. Spring Boot already watches reloadable bundles
(`spring.ssl.bundle.pem.<name>.reload-on-update=true`) and calls back through
`SslBundles.addBundleUpdateHandler`.

## Depends on
010 (TLS via `SslBundle`).

## Goal
A running adapter picks up rotated certificate material: clients connecting after the
rotation are served the new certificate, and connections already established are not
dropped.

## Key references
- `.docs/design/architecture.md` §8.2 (this is the follow-up named at the end of it).
- `org.springframework.boot.ssl.SslBundles#addBundleUpdateHandler`,
  `org.springframework.boot.autoconfigure.ssl.FileWatcher`.

## Steps / deliverables
1. Decide where the new certificate has to take effect. The `SSLServerSocket` fixes its
   `SSLContext` when it is bound, so it is the *listening socket* that has to be replaced,
   not just the factory — the two candidates are:
   - rebind: register a handler that stops accepting, closes the listening socket, and
     binds a new one from the updated bundle, keeping the already-accepted connections
     alive (the port is briefly unbound, so consider `SO_REUSEADDR` and a short accept
     pause rather than a full `stop()`/`start()`);
   - per-connection context: keep the socket and wrap each accepted plain `Socket` in an
     `SSLSocket` created from the *current* `SSLContext`, which moves the TLS handshake
     into the connection layer of the core and is a much larger change to the seam.
   Prefer the first unless it proves unworkable; the core must stay Spring-free either way.
2. Whichever is chosen, a rotation must not need `redis-adapter.ssl.*` to change, and a
   failure to load the new material must leave the old one serving rather than the port
   dead.
3. Log a rotation at INFO, with the bundle name.

## Acceptance criteria (tests first)
- A test that boots the server on a reloadable PEM bundle whose files live in a `@TempDir`,
  connects a client, replaces the certificate files with a second certificate, and shows
  that a client connecting afterwards is served the new certificate (compare the peer
  certificate's serial or subject), without the application context being restarted.
- A connection established before the rotation keeps working through it.
- Replacing the files with unreadable material leaves the previous certificate being
  served.

## Notes / gotchas
- The reload is Spring Boot's; do not watch files here.
- Native image (task 014) has to keep whatever watching mechanism this uses working, or the
  test above has to be excluded there knowingly rather than silently.
