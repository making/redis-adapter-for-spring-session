package am.ik.redis.adapter.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.pubsub.GlobPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server introspection commands a client may probe the adapter with: {@code COMMAND}
 * and {@code CONFIG}.
 *
 * <p>
 * The adapter implements the subset of Redis that Spring Session uses, so it publishes no
 * command table. {@code COMMAND} therefore answers something deliberately empty but
 * well-formed: a client that probes gets a valid answer instead of an error.
 *
 * <p>
 * {@code CONFIG} carries one parameter that matters. Before it subscribes, Spring Session
 * reads {@code notify-keyspace-events} and, if the flags do not already say that key
 * events are published, writes them — and it turns a failure of either call into a
 * start-up failure. The adapter always emits its {@code del} and {@code expired} events,
 * so it reports the flags that say so ({@code Egx}) and the client is spared the write. A
 * value a client does write is remembered, so that reading it back shows what was
 * written, but it never changes what the adapter emits.
 *
 * <p>
 * The remembered parameters belong to the dispatcher, which every connection of a server
 * shares, so {@code CONFIG SET} on one connection is visible from all of them — as a
 * server-wide setting should be.
 */
public final class ServerCommands {

	private static final Logger logger = LoggerFactory.getLogger(ServerCommands.class);

	private static final String NOTIFY_KEYSPACE_EVENTS = "notify-keyspace-events";

	/**
	 * Keyspace events on ({@code E}) for generic ({@code g}) and expired ({@code x}) key
	 * events — what Spring Session insists on, and what the adapter does whatever this
	 * says.
	 */
	private static final String DEFAULT_NOTIFY_KEYSPACE_EVENTS = "Egx";

	private final Map<String, String> configuration = new ConcurrentHashMap<>(
			Map.of(NOTIFY_KEYSPACE_EVENTS, DEFAULT_NOTIFY_KEYSPACE_EVENTS));

	private ServerCommands() {
	}

	/**
	 * Registers every server command on a dispatcher, along with the configuration the
	 * connections of that dispatcher share.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		ServerCommands commands = new ServerCommands();
		builder.register("COMMAND", ServerCommands::command).register("CONFIG", commands::config);
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
	 * {@code CONFIG <subcommand>}: {@code GET} reports the parameters the adapter keeps
	 * and {@code SET} remembers them. Any other subcommand is acknowledged rather than
	 * refused, because none of them changes how the adapter behaves and a client must not
	 * fail its start-up over one.
	 */
	private void config(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments("config");
		}
		String subcommand = CommandArguments.upperCase(argv.get(1));
		switch (subcommand) {
			case "GET" -> configGet(context, argv);
			case "SET" -> configSet(context, argv);
			default -> {
				logger.debug("Acknowledging CONFIG subcommand '{}'", subcommand);
				context.writer().writeSimpleString("OK");
			}
		}
	}

	/**
	 * {@code CONFIG GET parameter [parameter ...]}: replies with the parameters whose
	 * name matches any of the given globs, as a map. A parameter the adapter does not
	 * keep is simply absent — never an error.
	 */
	private void configGet(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 3) {
			throw RedisCommandException.wrongNumberOfArguments("config|get");
		}
		Map<String, String> matched = new LinkedHashMap<>();
		for (Map.Entry<String, String> parameter : this.configuration.entrySet()) {
			if (matchesAny(argv.subList(2, argv.size()), parameter.getKey())) {
				matched.put(parameter.getKey(), parameter.getValue());
			}
		}
		RespWriter writer = context.writer();
		writer.writeMapHeader(matched.size());
		for (Map.Entry<String, String> parameter : matched.entrySet()) {
			writeAscii(writer, parameter.getKey());
			writeAscii(writer, parameter.getValue());
		}
	}

	/**
	 * {@code CONFIG SET parameter value [parameter value ...]}: remembers the values and
	 * acknowledges. Parameters the adapter has no notion of are remembered too: reading
	 * back what was written is the only behaviour a client can observe here.
	 */
	private void configSet(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 4 || argv.size() % 2 != 0) {
			throw RedisCommandException.wrongNumberOfArguments("config|set");
		}
		for (int i = 2; i < argv.size(); i += 2) {
			String parameter = CommandArguments.text(argv.get(i)).toLowerCase(Locale.ROOT);
			this.configuration.put(parameter, CommandArguments.text(argv.get(i + 1)));
			logger.debug("Remembering CONFIG SET of '{}', which does not change what the adapter emits", parameter);
		}
		context.writer().writeSimpleString("OK");
	}

	private static boolean matchesAny(List<byte[]> patterns, String parameter) {
		byte[] name = parameter.getBytes(StandardCharsets.UTF_8);
		for (byte[] pattern : patterns) {
			if (GlobPattern.matches(lowerCase(pattern), name)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] lowerCase(byte[] pattern) {
		return CommandArguments.text(pattern).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
	}

	private static void writeAscii(RespWriter writer, String text) throws IOException {
		writer.writeBulk(text.getBytes(StandardCharsets.UTF_8));
	}

}
