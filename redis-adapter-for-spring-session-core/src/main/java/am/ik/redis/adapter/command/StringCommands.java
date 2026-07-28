package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;
import org.jspecify.annotations.Nullable;

/**
 * The string commands: {@code APPEND}, which Spring Session uses, and {@code SET} /
 * {@code GET}, which it does not.
 *
 * <p>
 * {@code APPEND} is not used to build up text. Appending nothing to a key that does not
 * exist is how Spring Session <em>creates</em> the shadow key that carries a session's
 * expiry — the key ends up holding a zero-length string, and from then on it exists,
 * takes a TTL, and can be renamed and deleted like any other. Creating the key is
 * therefore the whole point, and the returned length is incidental.
 *
 * <p>
 * {@code SET} and {@code GET} are here for the person holding a {@code redis-cli}: a
 * server nobody can put a value into and read it back out of cannot be tried out, only
 * pointed at an application. They are the two commands that make the adapter answerable
 * by hand, and every backend implements them, so what a demo shows is the backend rather
 * than a special case. Neither is on any path Spring Session takes.
 *
 * <p>
 * {@code SET} takes the four expiry options — {@code EX}, {@code PX}, {@code EXAT} and
 * {@code PXAT} — and no others. They differ only in unit and in whether they count from
 * now, so all four become the one absolute deadline
 * {@link am.ik.redis.adapter.store.KeyValueStore#set} writes with the value, in a single
 * operation of the store: a backend spread over several nodes could not honour a deadline
 * by following the write with a second round trip, because a process that dies between
 * the two leaves behind a key that never expires.
 *
 * <p>
 * {@code NX} / {@code XX}, {@code KEEPTTL} and {@code GET} are refused. Each is a
 * conditional write, or a read folded into one, that the SPI does not express — and a
 * store cannot be asked for it in two round trips for the same reason. A syntax error is
 * what they get, rather than semantics they do not have.
 */
public final class StringCommands {

	/** What Redis replies to an option it does not know, or a combination it refuses. */
	private static final String SYNTAX_ERROR = "ERR syntax error";

	private static final long MILLIS_PER_SECOND = 1000;

	private StringCommands() {
	}

	/**
	 * Registers every string command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("SET", StringCommands::set)
			.register("GET", StringCommands::get)
			.register("APPEND", StringCommands::append);
	}

	/**
	 * {@code SET key value [EX seconds | PX milliseconds | EXAT unix-time-seconds | PXAT
	 * unix-time-milliseconds]}: stores the value, replacing whatever the key held, with
	 * the deadline asked for and no other.
	 */
	private static void set(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 3) {
			throw RedisCommandException.wrongNumberOfArguments("set");
		}
		context.store().set(argv.get(1), argv.get(2), deadline(context, argv));
		context.writer().writeSimpleString("OK");
	}

	/**
	 * Reads whatever follows the value as the one expiry option {@code SET} accepts.
	 * @return the absolute deadline in epoch milliseconds, or {@code null} if no expiry
	 * was asked for
	 * @throws RedisCommandException if what follows the value is not exactly one expiry
	 * option and its argument
	 */
	private static @Nullable Long deadline(CommandContext context, List<byte[]> argv) {
		if (argv.size() == 3) {
			return null;
		}
		// Everything else lands here: an option with no argument, two of them, and the
		// options that are refused outright.
		if (argv.size() != 5) {
			throw new RedisCommandException(SYNTAX_ERROR);
		}
		Expiry expiry = Expiry.of(CommandArguments.upperCase(argv.get(3)));
		if (expiry == null) {
			throw new RedisCommandException(SYNTAX_ERROR);
		}
		long amount = CommandArguments.integer(argv.get(4));
		// Redis refuses a non-positive argument to all four, an absolute one included,
		// and
		// says so before the key is touched.
		if (amount <= 0) {
			throw invalidExpireTime();
		}
		try {
			return expiry.deadline(amount, context.store().currentTimeMillis());
		}
		catch (ArithmeticException e) {
			throw invalidExpireTime();
		}
	}

	private static RedisCommandException invalidExpireTime() {
		return new RedisCommandException("ERR invalid expire time in 'set' command");
	}

	/**
	 * {@code GET key}: replies with the string, or a null if the key is absent.
	 */
	private static void get(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("get");
		}
		byte[] value = switch (context.store().get(argv.get(1))) {
			case null -> null;
			case StringValue string -> string.value();
			case RedisValue other -> throw new TypeMismatchException(
					"GET against a key that does not hold a string: " + other.getClass().getSimpleName());
		};
		context.writer().writeBulk(value);
	}

	/**
	 * {@code APPEND key value}: appends to the string, creating it from {@code value} if
	 * the key is absent, and replies with the resulting length. Any existing expiry is
	 * left alone.
	 */
	private static void append(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 3) {
			throw RedisCommandException.wrongNumberOfArguments("append");
		}
		context.writer().writeInteger(context.store().append(argv.get(1), argv.get(2)));
	}

	/**
	 * The four spellings of a {@code SET} deadline, which differ only in the unit of
	 * their argument and in whether it counts from now or from the epoch. The store works
	 * in absolute milliseconds alone, so all four arrive there as one number.
	 */
	private enum Expiry {

		/** {@code EX seconds}. */
		EX(MILLIS_PER_SECOND, true),

		/** {@code PX milliseconds}. */
		PX(1, true),

		/** {@code EXAT unix-time-seconds}. */
		EXAT(MILLIS_PER_SECOND, false),

		/** {@code PXAT unix-time-milliseconds}. */
		PXAT(1, false);

		private final long millisPerUnit;

		private final boolean relative;

		Expiry(long millisPerUnit, boolean relative) {
			this.millisPerUnit = millisPerUnit;
			this.relative = relative;
		}

		/**
		 * Returns the option of that name, which is the name of the constant itself.
		 * @param option the upper-cased option as it arrived
		 * @return the option, or {@code null} if it is not one
		 */
		static @Nullable Expiry of(String option) {
			for (Expiry expiry : values()) {
				if (expiry.name().equals(option)) {
					return expiry;
				}
			}
			return null;
		}

		/**
		 * Converts the option's argument to the absolute deadline the store works in.
		 * @param amount the argument, which the caller has already refused to be
		 * non-positive
		 * @param now the store's clock, which the relative variants count from
		 * @return the absolute deadline in epoch milliseconds
		 * @throws ArithmeticException if the deadline does not fit in a {@code long}
		 */
		long deadline(long amount, long now) {
			return Math.addExact(this.relative ? now : 0, Math.multiplyExact(amount, this.millisPerUnit));
		}

	}

}
