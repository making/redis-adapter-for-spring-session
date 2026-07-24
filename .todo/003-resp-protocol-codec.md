# 003 — RESP protocol codec

## Context
The adapter speaks the Redis serialization protocol (RESP) over TCP. This task implements a
standalone, unit-testable reader/writer for RESP2 and the RESP3 subset Lettuce needs. No
sockets and no command logic yet — just bytes ⇄ RESP elements.

## Depends on
001. Independent of 002 (can run in parallel).

## Goal
`am.ik.redis.adapter.protocol` with a `RespReader` (parse requests) and `RespWriter`
(encode replies), covered by byte-level tests.

## Key references
- Redis protocol spec (RESP2 + RESP3). Summary of what we need below.
- `.docs/design/redis-command-surface.md` (which reply shapes each command returns).

## Background: RESP essentials
- **Requests** from clients are always **arrays of bulk strings**:
  `*<n>\r\n` then n × `$<len>\r\n<bytes>\r\n`. Parse into `List<byte[]>` (argv), where
  `argv[0]` is the command name (case-insensitive), rest are arguments (raw bytes).
- Inline commands (space-separated, no `*`) are legacy; Lettuce does not use them, but a
  tolerant reader may accept them. Optional.
- **Replies** we must be able to write:
  - Simple string: `+OK\r\n`
  - Error: `-ERR message\r\n` (and typed like `-WRONGTYPE ...`)
  - Integer: `:123\r\n`
  - Bulk string: `$<len>\r\n<bytes>\r\n`; null bulk `$-1\r\n` (RESP2) / `_\r\n` (RESP3)
  - Array: `*<n>\r\n` + elements; null array `*-1\r\n`
  - RESP3 additions used by handshake/pubsub: map `%<n>\r\n`, push `><n>\r\n`,
    double `,<v>\r\n`, big-number/boolean as needed. Implement the ones `HELLO`,
    pub/sub push, and `CONFIG GET` need; others can wait.

## Steps / deliverables
1. A small element model or a fluent `RespWriter` over an `OutputStream`/byte buffer:
   `writeSimpleString`, `writeError`, `writeInteger`, `writeBulk(byte[]|null)`,
   `writeArray(...)`, `writeMap(...)`, `writePush(...)`, `writeNull()` (protocol-version
   aware for null and map/push).
2. `RespReader` over an `InputStream`: `List<byte[]> readCommand()` returning the next
   request argv, or null on clean EOF. Must handle partial reads / buffering and large
   bulk strings. Be strict about `\r\n` framing; throw a clear protocol error otherwise.
3. Carry the negotiated protocol version (RESP2 default, RESP3 after `HELLO 3`) so the
   writer picks the right null/map/push encoding. The version lives on the connection
   (task 004) and is passed to the writer.

## Acceptance criteria (tests first)
- Round-trip: encode each reply type and assert exact bytes against fixtures.
- Parse `*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n` → `["SET","a","b"]` (as bytes).
- Parse a command split across multiple `InputStream` reads (feed bytes in chunks).
- Binary-safe bulk: values containing `\r`, `\n`, `\0`, and arbitrary bytes round-trip.
- Empty array, null bulk, and (RESP3) map/push encode correctly for both versions.
- Malformed input (bad length, missing CRLF) raises a distinct protocol exception.

## Notes / gotchas
- Everything is bytes; never `new String` a value. Command name comparison is
  ASCII-case-insensitive (`SUBSCRIBE` == `subscribe`).
- Keep the reader allocation-reasonable but do not micro-optimize; virtual threads make a
  simple blocking `BufferedInputStream` design fine.
- Do not couple the codec to command semantics — it only moves bytes.
