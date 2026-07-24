package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server introspection commands a client may probe the adapter with: {@code COMMAND}
 * and {@code CONFIG}.
 *
 * <p>
 * The adapter implements the subset of Redis that Spring Session uses, so it does not
 * publish a command table or a configuration. These replies are deliberately empty but
 * well-formed: a client that probes gets a valid answer instead of an error.
 */
public final class ServerCommands {

	private static final Logger logger = LoggerFactory.getLogger(ServerCommands.class);

	private ServerCommands() {
	}

	/**
	 * Registers every server command on a dispatcher.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("COMMAND", ServerCommands::command).register("CONFIG", ServerCommands::config);
	}

	/**
	 * {@code COMMAND [COUNT|DOCS|INFO ...]}: replies that no command table is published.
	 * {@code COMMAND INFO} answers one null per requested command, which is what Redis
	 * replies for a command it does not know.
	 */
	private static void command(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() == 1) {
			context.writer().writeArrayHeader(0);
			return;
		}
		String subcommand = CommandArguments.upperCase(argv.get(1));
		switch (subcommand) {
			case "COUNT" -> context.writer().writeInteger(0);
			case "DOCS" -> context.writer().writeMapHeader(0);
			case "INFO" -> {
				int requested = argv.size() - 2;
				context.writer().writeArrayHeader(requested);
				for (int i = 0; i < requested; i++) {
					context.writer().writeNullArray();
				}
			}
			default -> {
				logger.debug("Answering unhandled COMMAND subcommand '{}' with an empty array", subcommand);
				context.writer().writeArrayHeader(0);
			}
		}
	}

	/**
	 * {@code CONFIG <subcommand>}: {@code GET} reports that no parameter is set and
	 * everything else is acknowledged. Clients read and write server parameters during
	 * start-up (notably the keyspace notification flags) and must not fail over them; the
	 * adapter's behaviour does not depend on any of them.
	 */
	private static void config(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments("config");
		}
		String subcommand = CommandArguments.upperCase(argv.get(1));
		if ("GET".equals(subcommand)) {
			if (argv.size() < 3) {
				throw RedisCommandException.wrongNumberOfArguments("config|get");
			}
			context.writer().writeMapHeader(0);
		}
		else {
			logger.debug("Acknowledging CONFIG subcommand '{}'", subcommand);
			context.writer().writeSimpleString("OK");
		}
	}

}
