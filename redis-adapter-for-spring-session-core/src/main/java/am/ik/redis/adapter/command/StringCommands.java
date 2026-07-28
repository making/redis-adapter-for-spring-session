package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

import am.ik.redis.adapter.store.RedisValue;
import am.ik.redis.adapter.store.StringValue;
import am.ik.redis.adapter.store.TypeMismatchException;

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
 * {@code SET} takes no options. {@code EX}, {@code NX}, {@code KEEPTTL} and the rest are
 * each a conditional or combined write the
 * {@link am.ik.redis.adapter.store.KeyValueStore} SPI does not express, and a backend
 * spread over several nodes cannot honour them by following the write with a second round
 * trip — so a syntax error is what they get, rather than semantics they do not have.
 * {@code EXPIRE} and {@code PEXPIRE} put a deadline on a key that is already there.
 */
public final class StringCommands {

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
	 * {@code SET key value}: stores the value, replacing whatever the key held and any
	 * expiry it had.
	 */
	private static void set(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 3) {
			throw RedisCommandException.wrongNumberOfArguments("set");
		}
		if (argv.size() > 3) {
			throw new RedisCommandException("ERR syntax error");
		}
		context.store().set(argv.get(1), argv.get(2));
		context.writer().writeSimpleString("OK");
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

}
