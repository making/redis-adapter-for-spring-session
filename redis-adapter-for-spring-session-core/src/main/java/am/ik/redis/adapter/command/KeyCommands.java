package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

import am.ik.redis.adapter.store.HashValue;
import am.ik.redis.adapter.store.KeyValueStore;
import am.ik.redis.adapter.store.SetValue;
import am.ik.redis.adapter.store.StringValue;

/**
 * The key-level commands, which are what drives a session's lifetime: {@code EXISTS},
 * {@code DEL} / {@code UNLINK}, {@code RENAME}, {@code TYPE} and the expiry family
 * ({@code EXPIRE}, {@code PEXPIRE}, {@code EXPIREAT}, {@code PEXPIREAT}, {@code PERSIST},
 * {@code TTL}, {@code PTTL}).
 *
 * <p>
 * The store works in <strong>absolute</strong> deadlines only, so the relative and
 * second-based variants are converted here against
 * {@link KeyValueStore#currentTimeMillis()} — the store's own clock, so that a store with
 * a test clock expires consistently with what these commands wrote.
 *
 * <p>
 * Every read honours the store's passive expiry: a key whose deadline has passed is
 * evicted, and its expiry event fired, at the moment it is touched. That is exactly why
 * Spring Session's background cleanup touches keys with {@code EXISTS}.
 */
public final class KeyCommands {

	/** What Redis replies when {@code RENAME} is given a source that is not there. */
	private static final String NO_SUCH_KEY = "ERR no such key";

	/** Reply of {@code TTL} / {@code PTTL} for a key that exists but never expires. */
	private static final long NO_EXPIRY = -1;

	/** Reply of {@code TTL} / {@code PTTL} for a key that does not exist. */
	private static final long NO_KEY = -2;

	private static final long MILLIS_PER_SECOND = 1000;

	private KeyCommands() {
	}

	/**
	 * Registers every key command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("EXISTS", KeyCommands::exists)
			.register("DEL", KeyCommands::del)
			.register("UNLINK", KeyCommands::del)
			.register("RENAME", KeyCommands::rename)
			.register("TYPE", KeyCommands::type)
			.register("PERSIST", KeyCommands::persist)
			.register("EXPIRE", (context, argv) -> expire(context, argv, "expire", MILLIS_PER_SECOND, true))
			.register("PEXPIRE", (context, argv) -> expire(context, argv, "pexpire", 1, true))
			.register("EXPIREAT", (context, argv) -> expire(context, argv, "expireat", MILLIS_PER_SECOND, false))
			.register("PEXPIREAT", (context, argv) -> expire(context, argv, "pexpireat", 1, false))
			.register("TTL", (context, argv) -> ttl(context, argv, "ttl", false))
			.register("PTTL", (context, argv) -> ttl(context, argv, "pttl", true));
	}

	/**
	 * {@code EXISTS key [key ...]}: replies with how many of the given keys exist,
	 * counting a key named twice twice, as Redis does.
	 */
	private static void exists(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments("exists");
		}
		KeyValueStore store = context.store();
		long found = 0;
		for (byte[] key : argv.subList(1, argv.size())) {
			if (store.exists(key)) {
				found++;
			}
		}
		context.writer().writeInteger(found);
	}

	/**
	 * {@code DEL key [key ...]} (and {@code UNLINK}, which differs only in reclaiming
	 * memory in the background): removes the keys and replies with how many were actually
	 * there. Each removal fires the store's delete event, which becomes the {@code del}
	 * keyspace notification Spring Session's session-deleted event depends on.
	 */
	private static void del(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments("del");
		}
		KeyValueStore store = context.store();
		long removed = 0;
		for (byte[] key : argv.subList(1, argv.size())) {
			if (store.delete(key)) {
				removed++;
			}
		}
		context.writer().writeInteger(removed);
	}

	/**
	 * {@code RENAME old new}: moves the value and its expiry, overwriting the
	 * destination. A missing source is an error whose text Spring Session matches on when
	 * it renames a session that has already gone, so the wording is part of the contract.
	 */
	private static void rename(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 3) {
			throw RedisCommandException.wrongNumberOfArguments("rename");
		}
		if (!context.store().rename(argv.get(1), argv.get(2))) {
			throw new RedisCommandException(NO_SUCH_KEY);
		}
		context.writer().writeSimpleString("OK");
	}

	/**
	 * {@code TYPE key}: names what the key holds, or {@code none} if it holds nothing.
	 */
	private static void type(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("type");
		}
		String name = switch (context.store().get(argv.get(1))) {
			case null -> "none";
			case StringValue ignored -> "string";
			case HashValue ignored -> "hash";
			case SetValue ignored -> "set";
		};
		context.writer().writeSimpleString(name);
	}

	/**
	 * {@code PERSIST key}: clears the expiry, replying {@code 1} only if there was one to
	 * clear.
	 */
	private static void persist(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("persist");
		}
		context.writer().writeInteger(context.store().persist(argv.get(1)) ? 1 : 0);
	}

	/**
	 * The four expiry commands, which differ only in the unit of their argument and in
	 * whether it counts from now or from the epoch.
	 * @param command the command name, for error messages
	 * @param millisPerUnit {@code 1} for the millisecond variants, {@code 1000} for the
	 * second ones
	 * @param relative whether the argument counts from now rather than from the epoch
	 */
	private static void expire(CommandContext context, List<byte[]> argv, String command, long millisPerUnit,
			boolean relative) throws IOException {
		if (argv.size() != 3) {
			throw RedisCommandException.wrongNumberOfArguments(command);
		}
		KeyValueStore store = context.store();
		long origin = relative ? store.currentTimeMillis() : 0;
		long deadline;
		try {
			deadline = Math.addExact(origin, Math.multiplyExact(CommandArguments.integer(argv.get(2)), millisPerUnit));
		}
		catch (ArithmeticException e) {
			throw new RedisCommandException("ERR invalid expire time in '" + command + "' command");
		}
		context.writer().writeInteger(store.expireAt(argv.get(1), deadline) ? 1 : 0);
	}

	/**
	 * {@code TTL key} / {@code PTTL key}: how long the key has left, {@code -1} if it
	 * never expires and {@code -2} if it is not there. Seconds are rounded to the nearest
	 * whole second, as Redis rounds them.
	 * @param command the command name, for error messages
	 * @param millis whether to answer in milliseconds rather than seconds
	 */
	private static void ttl(CommandContext context, List<byte[]> argv, String command, boolean millis)
			throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments(command);
		}
		KeyValueStore store = context.store();
		byte[] key = argv.get(1);
		Long expireAt = store.getExpireAt(key);
		if (expireAt == null) {
			context.writer().writeInteger(store.exists(key) ? NO_EXPIRY : NO_KEY);
			return;
		}
		long remaining = Math.max(0, expireAt - store.currentTimeMillis());
		context.writer().writeInteger(millis ? remaining : (remaining + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND);
	}

}
