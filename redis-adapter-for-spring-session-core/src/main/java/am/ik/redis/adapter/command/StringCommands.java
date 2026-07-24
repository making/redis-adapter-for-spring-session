package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

/**
 * The string commands, of which Spring Session uses exactly one: {@code APPEND}.
 *
 * <p>
 * It is not used to build up text. Appending nothing to a key that does not exist is how
 * Spring Session <em>creates</em> the shadow key that carries a session's expiry — the
 * key ends up holding a zero-length string, and from then on it exists, takes a TTL, and
 * can be renamed and deleted like any other. Creating the key is therefore the whole
 * point, and the returned length is incidental.
 */
public final class StringCommands {

	private StringCommands() {
	}

	/**
	 * Registers every string command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("APPEND", StringCommands::append);
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
