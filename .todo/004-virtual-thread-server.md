# 004 — Virtual-thread TCP server + handshake commands

## Context
Wire the RESP codec (003) into a real TCP server that a Lettuce client can connect to. This
task builds the accept loop, the per-connection read→dispatch→write loop, connection state,
and the **handshake/session commands** a Lettuce client sends before any data command. No
session data commands yet (those are 005) — but the dispatcher and a real client handshake
must work end to end.

## Depends on
003 (codec). Loosely 002 (a store handle is passed through but not exercised yet).

## Goal
`am.ik.redis.adapter.server` with `RedisAdapterServer` (start/stop, virtual-thread per
connection) and `ClientConnection`, plus a `CommandDispatcher` skeleton and handshake
command handlers. A real Lettuce client can connect, `HELLO`, `PING`, `SELECT`, and
gracefully `QUIT`.

## Key references
- `.docs/design/redis-command-surface.md` §E (handshake/server commands).
- `.docs/research/04-configuration-and-serializers.md` §3 (CONFIG GET/SET usage — the actual
  CONFIG handling can be stubbed here and completed in 006).

## Steps / deliverables
1. `RedisAdapterServer`:
   - Takes an injected `javax.net.ServerSocketFactory` (default
     `ServerSocketFactory.getDefault()`), and binds via `factory.createServerSocket(...)`
     to a configurable host/port. This is the **TLS seam**: task 010 injects an
     `SSLServerSocketFactory` here with no other change to the server. Do not hard-code
     `new ServerSocket(...)`.
   - `start()` runs the accept loop on its own thread; each accepted socket is handled by a
     task submitted to `Executors.newVirtualThreadPerTaskExecutor()`. The loop must work
     unchanged whether the accepted socket is a plain `Socket` or an `SSLSocket`.
   - Clean `close()`/`stop()`: stop accepting, interrupt/close client connections, close
     the executor. No leaked threads.
2. `ClientConnection` (one per socket, runs on one virtual thread):
   - Owns: input/output streams (buffered), RESP protocol version (default RESP2),
     selected DB index (default 0), subscription state (filled in 006), and a reference to
     the `CommandDispatcher`/store.
   - Loop: `argv = reader.readCommand()`; if null → close. Else dispatch and flush the
     reply. Catch protocol/command errors and write a RESP error frame instead of dropping
     the connection where Redis would keep it open.
3. `CommandDispatcher`: map command name (case-insensitive) → handler. Unknown command →
   `-ERR unknown command '<name>'` (and **log it at debug** — this log is how task 004/005
   discover which handshake commands Lettuce actually sends).
4. Handshake/server command handlers (minimal but real):
   - `PING [msg]` → `+PONG` or bulk echo.
   - `HELLO [proto [AUTH u p] [SETNAME n]]` → switch protocol version; reply the server
     info map (`server`, `version`, `proto`, `id`, `mode`, `role`, `modules`). Support
     `HELLO 2` and `HELLO 3`.
   - `AUTH` → if no password configured, reply `+OK` (or the Redis-accurate
     `-ERR Client sent AUTH, but no password is set` — pick what keeps Lettuce happy; a
     no-password server that returns OK is simplest and Lettuce only sends AUTH when
     configured).
   - `CLIENT <SETINFO|SETNAME|ID|...>` → `+OK` (or an integer for `ID`).
   - `SELECT db` → set connection DB index, `+OK`.
   - `QUIT` → `+OK`, then close.
   - `COMMAND [DOCS|COUNT|INFO]` → minimal reply (empty array / `:0`) so Lettuce does not
     choke if it probes.
   - `CONFIG GET/SET` → stub here (return empty / `+OK`); real `notify-keyspace-events`
     handling lands in 006.

## Acceptance criteria (tests first)
- Unit: dispatcher routes by case-insensitive name; unknown → error frame.
- Integration (this is the key one): boot `RedisAdapterServer` on an ephemeral port, create
  a **real Lettuce** `RedisClient` (test-scoped dep) pointed at it, and assert
  `sync.ping()` returns `PONG`, a RESP3 `HELLO`/protocol negotiation succeeds, `SELECT`
  works, and closing the client is clean. Capture and print any "unknown command" the
  Lettuce handshake triggers and implement it.
- Server shutdown closes all connections and threads (no hang in test teardown).

## Notes / gotchas
- Add Lettuce as a **test-scoped** dependency of `core` for the integration test (it is a
  testing library here; the main classpath stays clean).
- Lettuce version follows the Spring Boot BOM already imported in the POM.
- Replies must be flushed per command (request/response ordering per connection is
  guaranteed by the single reader thread).
- Keep per-connection state off shared mutable fields; the store/pubsub registry are the
  only shared components.
