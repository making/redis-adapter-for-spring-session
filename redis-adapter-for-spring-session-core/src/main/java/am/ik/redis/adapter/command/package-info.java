/**
 * Command layer: the command dispatcher and per-command handlers.
 *
 * <p>
 * This package implements the minimal subset of Redis commands that Spring Session
 * actually uses, together with the shared command context and RESP error formatting (for
 * example {@code ERR no such key} and {@code WRONGTYPE}). Handlers translate parsed RESP
 * requests into calls against the {@code KeyValueStore} and produce RESP replies.
 *
 * <p>
 * This package is null-marked: all types and their members are non-null by default unless
 * explicitly annotated as {@code @Nullable}.
 */
@NullMarked
package am.ik.redis.adapter.command;

import org.jspecify.annotations.NullMarked;
