package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import am.ik.redis.adapter.store.TypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes a request to the {@link CommandHandler} registered for its command name and
 * turns a failing handler into a RESP error reply.
 *
 * <p>
 * Command names are matched case-insensitively, as Redis matches them. A request for a
 * command that is not registered is answered with {@code ERR unknown command '<name>'}
 * and logged at debug level, which is how the exact set of commands a client sends is
 * discovered.
 *
 * <p>
 * The dispatcher is also where authentication is enforced: a command registered with
 * {@link Builder#register} is answered with {@code NOAUTH Authentication required.} until
 * the connection has authenticated, so no handler has to check for itself. The handshake
 * commands a client needs in order to authenticate at all are registered with
 * {@link Builder#registerUnauthenticated} instead. An unknown command is reported as
 * unknown even to a connection that has not authenticated, matching Redis.
 *
 * <p>
 * Every failure mode ends in a reply rather than a broken connection: a
 * {@link RedisCommandException} carries its own wire text, a
 * {@link TypeMismatchException} from the backend becomes {@code WRONGTYPE}, and anything
 * else becomes {@code ERR internal error} and is logged with its stack trace. Only an
 * {@link IOException} — a dead transport — propagates.
 *
 * <p>
 * Instances are immutable and shared by every connection.
 */
public final class CommandDispatcher {

	private static final Logger logger = LoggerFactory.getLogger(CommandDispatcher.class);

	private static final String WRONG_TYPE = "WRONGTYPE Operation against a key holding the wrong kind of value";

	private static final String NO_AUTH = "NOAUTH Authentication required.";

	private final Map<String, Command> commands;

	private CommandDispatcher(Map<String, Command> commands) {
		this.commands = Map.copyOf(commands);
	}

	/**
	 * Returns a builder to register command handlers on.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Executes one request and writes exactly one reply.
	 * @param context the connection the request arrived on
	 * @param argv the argument vector, where element {@code 0} is the command name
	 * @throws IOException if the reply cannot be written
	 * @throws IllegalArgumentException if {@code argv} is empty
	 */
	public void dispatch(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.isEmpty()) {
			throw new IllegalArgumentException("argv must contain at least the command name");
		}
		String name = CommandArguments.upperCase(argv.get(0));
		Command command = this.commands.get(name);
		if (command == null) {
			logger.debug("Unknown command '{}' with {} argument(s)", name, argv.size() - 1);
			context.writer().writeError("ERR unknown command '" + CommandArguments.display(argv.get(0)) + "'");
			return;
		}
		if (command.requiresAuthentication() && !context.authenticated()) {
			logger.debug("Rejecting '{}' on a connection that has not authenticated", name);
			context.writer().writeError(NO_AUTH);
			return;
		}
		try {
			command.handler().handle(context, argv);
		}
		catch (RedisCommandException e) {
			context.writer().writeError(e.errorMessage());
		}
		catch (TypeMismatchException e) {
			logger.debug("Command '{}' hit a type mismatch: {}", name, e.toString());
			context.writer().writeError(WRONG_TYPE);
		}
		catch (RuntimeException e) {
			logger.warn("Command '{}' failed unexpectedly", name, e);
			context.writer().writeError("ERR internal error");
		}
	}

	/**
	 * Reports whether a command is registered.
	 * @param name the command name, matched case-insensitively
	 * @return {@code true} if a handler is registered for the name
	 */
	public boolean supports(String name) {
		return this.commands.containsKey(name.toUpperCase(Locale.ROOT));
	}

	/**
	 * A registered command: what runs it, and whether the connection must have
	 * authenticated first.
	 */
	private record Command(CommandHandler handler, boolean requiresAuthentication) {
	}

	/**
	 * Builder for a {@link CommandDispatcher}.
	 */
	public static final class Builder {

		private final Map<String, Command> commands = new HashMap<>();

		private Builder() {
		}

		/**
		 * Registers a command that a connection may only run once it has authenticated.
		 * @param name the command name; it is matched case-insensitively at dispatch time
		 * @param handler the handler to run
		 * @return this builder
		 * @throws IllegalArgumentException if the command is already registered
		 */
		public Builder register(String name, CommandHandler handler) {
			return register(name, new Command(handler, true));
		}

		/**
		 * Registers a command that a connection may run before it has authenticated.
		 * Reserve this for the handshake itself: a client cannot authenticate without
		 * being allowed to send {@code AUTH} or {@code HELLO} first.
		 * @param name the command name; it is matched case-insensitively at dispatch time
		 * @param handler the handler to run
		 * @return this builder
		 * @throws IllegalArgumentException if the command is already registered
		 */
		public Builder registerUnauthenticated(String name, CommandHandler handler) {
			return register(name, new Command(handler, false));
		}

		private Builder register(String name, Command command) {
			String key = name.toUpperCase(Locale.ROOT);
			if (this.commands.putIfAbsent(key, command) != null) {
				throw new IllegalArgumentException("command is already registered: " + key);
			}
			return this;
		}

		/**
		 * Builds the dispatcher.
		 * @return a new dispatcher holding a copy of the registered commands
		 */
		public CommandDispatcher build() {
			return new CommandDispatcher(this.commands);
		}

	}

}
