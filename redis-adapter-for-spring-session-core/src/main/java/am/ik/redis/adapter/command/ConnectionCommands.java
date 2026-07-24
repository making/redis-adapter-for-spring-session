package am.ik.redis.adapter.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.protocol.RespWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection commands a client sends before, and alongside, its data commands:
 * {@code PING}, {@code HELLO}, {@code AUTH}, {@code CLIENT}, {@code SELECT} and
 * {@code QUIT}.
 *
 * <p>
 * These exist so that a stock Redis client completes its handshake and its periodic
 * health checks against the adapter. They only touch per-connection state, never the
 * backend.
 */
public final class ConnectionCommands {

	private static final Logger logger = LoggerFactory.getLogger(ConnectionCommands.class);

	/**
	 * The server identity reported by {@code HELLO}. Clients drive the adapter exactly as
	 * they drive Redis, so the adapter answers as Redis; the version is recent enough
	 * that clients do not disable features they would use against a current server.
	 */
	private static final String SERVER_NAME = "redis";

	private static final String SERVER_VERSION = "7.4.0";

	private static final String SERVER_MODE = "standalone";

	private static final String SERVER_ROLE = "master";

	/** What Redis answers a client whose credentials do not match. */
	private static final String WRONG_PASSWORD = "WRONGPASS invalid username-password pair or user is disabled.";

	/**
	 * What Redis answers a {@code HELLO} that arrives before the client authenticated.
	 */
	private static final String HELLO_REQUIRES_AUTHENTICATION = "NOAUTH HELLO must be called with the client already "
			+ "authenticated, otherwise the HELLO <proto> AUTH <user> <pass> option can be used to authenticate the "
			+ "client and select the RESP protocol version.";

	private ConnectionCommands() {
	}

	/**
	 * Registers every connection command on a dispatcher. {@code AUTH}, {@code HELLO} and
	 * {@code QUIT} are the commands a connection may send before it has authenticated:
	 * the first two are how it authenticates, and refusing to let a client hang up would
	 * help nobody. {@code PING} and {@code QUIT} also survive subscriber mode, because a
	 * subscribed connection still has to be able to prove it is alive and to leave.
	 * @param builder the dispatcher builder to register on
	 */
	public static void registerTo(CommandDispatcher.Builder builder) {
		builder.register("PING", ConnectionCommands::ping, CommandAvailability.WHILE_SUBSCRIBED)
			.register("HELLO", ConnectionCommands::hello, CommandAvailability.UNAUTHENTICATED)
			.register("AUTH", ConnectionCommands::auth, CommandAvailability.UNAUTHENTICATED)
			.register("CLIENT", ConnectionCommands::client)
			.register("SELECT", ConnectionCommands::select)
			.register("QUIT", ConnectionCommands::quit, CommandAvailability.UNAUTHENTICATED,
					CommandAvailability.WHILE_SUBSCRIBED);
	}

	/**
	 * {@code PING [message]}: replies {@code PONG}, or echoes the message back.
	 */
	private static void ping(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() > 2) {
			throw RedisCommandException.wrongNumberOfArguments("ping");
		}
		if (argv.size() == 2) {
			context.writer().writeBulk(argv.get(1));
		}
		else {
			context.writer().writeSimpleString("PONG");
		}
	}

	/**
	 * {@code HELLO [protover [AUTH user password] [SETNAME name]]}: negotiates the
	 * protocol version and replies with the server identity. Its {@code AUTH} option is
	 * the other way a client authenticates, so on a protected server a {@code HELLO}
	 * without it is refused. The version switches only once everything is accepted, so a
	 * rejected {@code HELLO} leaves the connection exactly as it was, as Redis does it.
	 */
	private static void hello(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() > 1) {
			RespVersion requested = protocolVersion(argv.get(1));
			readHelloOptions(context, argv);
			requireAuthentication(context);
			context.protocolVersion(requested);
		}
		else {
			requireAuthentication(context);
		}
		writeServerIdentity(context);
	}

	private static void requireAuthentication(CommandContext context) {
		if (!context.authenticated()) {
			throw new RedisCommandException(HELLO_REQUIRES_AUTHENTICATION);
		}
	}

	private static RespVersion protocolVersion(byte[] argument) {
		long requested;
		try {
			requested = Long.parseLong(CommandArguments.text(argument));
		}
		catch (NumberFormatException e) {
			throw new RedisCommandException("ERR Protocol version is not an integer or out of range");
		}
		try {
			return RespVersion.fromNumber(Math.toIntExact(requested));
		}
		catch (ArithmeticException | IllegalArgumentException e) {
			throw new RedisCommandException("NOPROTO unsupported protocol version");
		}
	}

	private static void readHelloOptions(CommandContext context, List<byte[]> argv) {
		int index = 2;
		while (index < argv.size()) {
			String option = CommandArguments.upperCase(argv.get(index));
			int moreArguments = argv.size() - index - 1;
			if ("AUTH".equals(option) && moreArguments >= 2) {
				authenticate(context, CommandArguments.text(argv.get(index + 1)),
						CommandArguments.text(argv.get(index + 2)));
				index += 3;
			}
			else if ("SETNAME".equals(option) && moreArguments >= 1) {
				context.clientName(CommandArguments.text(argv.get(index + 1)));
				index += 2;
			}
			else {
				throw new RedisCommandException(
						"ERR Syntax error in HELLO option '" + CommandArguments.display(argv.get(index)) + "'");
			}
		}
	}

	private static void writeServerIdentity(CommandContext context) throws IOException {
		RespWriter writer = context.writer();
		writer.writeMapHeader(7);
		writeAscii(writer, "server");
		writeAscii(writer, SERVER_NAME);
		writeAscii(writer, "version");
		writeAscii(writer, SERVER_VERSION);
		writeAscii(writer, "proto");
		writer.writeInteger(context.protocolVersion().number());
		writeAscii(writer, "id");
		writer.writeInteger(context.connectionId());
		writeAscii(writer, "mode");
		writeAscii(writer, SERVER_MODE);
		writeAscii(writer, "role");
		writeAscii(writer, SERVER_ROLE);
		writeAscii(writer, "modules");
		writer.writeArrayHeader(0);
	}

	/**
	 * {@code AUTH [username] password}: authenticates the connection. A client that sends
	 * only a password authenticates as the default user, as it does against Redis.
	 *
	 * <p>
	 * On a server with no password this accepts anything and replies {@code OK}. Redis
	 * answers an error instead, but a client configured with a password must still be
	 * able to reach an adapter that does not need one.
	 */
	private static void auth(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2 || argv.size() > 3) {
			throw RedisCommandException.wrongNumberOfArguments("auth");
		}
		String username = (argv.size() == 3) ? CommandArguments.text(argv.get(1)) : Authenticator.DEFAULT_USERNAME;
		authenticate(context, username, CommandArguments.text(argv.get(argv.size() - 1)));
		context.writer().writeSimpleString("OK");
	}

	private static void authenticate(CommandContext context, String username, String password) {
		if (!context.authenticate(username, password)) {
			throw new RedisCommandException(WRONG_PASSWORD);
		}
	}

	/**
	 * {@code CLIENT <subcommand>}: connection metadata. Subcommands that carry no meaning
	 * for the adapter are accepted rather than rejected, so that a client's optional
	 * bookkeeping never fails its handshake.
	 */
	private static void client(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() < 2) {
			throw RedisCommandException.wrongNumberOfArguments("client");
		}
		String subcommand = CommandArguments.upperCase(argv.get(1));
		switch (subcommand) {
			case "ID" -> context.writer().writeInteger(context.connectionId());
			case "GETNAME" -> {
				String clientName = context.clientName();
				context.writer().writeBulk((clientName == null) ? null : clientName.getBytes(StandardCharsets.UTF_8));
			}
			case "SETNAME" -> {
				if (argv.size() != 3) {
					throw RedisCommandException.wrongNumberOfArguments("client|setname");
				}
				context.clientName(CommandArguments.text(argv.get(2)));
				context.writer().writeSimpleString("OK");
			}
			case "SETINFO" -> {
				if (argv.size() != 4) {
					throw RedisCommandException.wrongNumberOfArguments("client|setinfo");
				}
				context.writer().writeSimpleString("OK");
			}
			default -> {
				logger.debug("Accepting unhandled CLIENT subcommand '{}'", subcommand);
				context.writer().writeSimpleString("OK");
			}
		}
	}

	/**
	 * {@code SELECT index}: switches the database subsequent commands operate on.
	 */
	private static void select(CommandContext context, List<byte[]> argv) throws IOException {
		if (argv.size() != 2) {
			throw RedisCommandException.wrongNumberOfArguments("select");
		}
		long index = CommandArguments.integer(argv.get(1));
		if (index < 0 || index >= context.databaseCount()) {
			throw new RedisCommandException("ERR DB index is out of range");
		}
		context.databaseIndex((int) index);
		context.writer().writeSimpleString("OK");
	}

	/**
	 * {@code QUIT}: acknowledges, then the server closes the connection once the reply is
	 * flushed.
	 */
	private static void quit(CommandContext context, List<byte[]> argv) throws IOException {
		context.writer().writeSimpleString("OK");
		context.requestClose();
	}

	private static void writeAscii(RespWriter writer, String text) throws IOException {
		writer.writeBulk(text.getBytes(StandardCharsets.US_ASCII));
	}

}
