package am.ik.redis.adapter.command;

import java.io.IOException;
import java.util.List;

/**
 * Executes one Redis command on behalf of a connection.
 *
 * <p>
 * A handler reads what it needs from the argument vector, applies the command to the
 * connection's {@link CommandContext#store() store} or state, and writes exactly one
 * reply to {@link CommandContext#writer()}. It never flushes and never closes the
 * connection itself; the server does both around the call.
 */
@FunctionalInterface
public interface CommandHandler {

	/**
	 * Runs the command and writes its reply.
	 * @param context the connection the command arrived on
	 * @param argv the argument vector as sent, where element {@code 0} is the command
	 * name; all elements are raw bytes
	 * @throws IOException if the reply cannot be written
	 * @throws RedisCommandException if the command is rejected, for example because of a
	 * bad argument; the dispatcher turns it into a RESP error reply and keeps the
	 * connection open
	 */
	void handle(CommandContext context, List<byte[]> argv) throws IOException;

}
