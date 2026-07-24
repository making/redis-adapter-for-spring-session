package am.ik.redis.adapter.command;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;

/**
 * The connection a command executes against: its mutable per-connection state plus the
 * writer its reply is sent on.
 *
 * <p>
 * The server layer implements this for a live TCP connection; tests implement it against
 * an in-memory buffer. Command handlers therefore never depend on sockets, which keeps
 * the command layer independent of the server layer.
 *
 * <p>
 * All state here belongs to exactly one connection and is only ever touched by that
 * connection's own thread while a command is executing, so implementations need no
 * synchronization for it.
 */
public interface CommandContext {

	/**
	 * Returns the writer the reply of the current command must be written to. The server
	 * flushes it once the command returns.
	 * @return the reply writer
	 */
	RespWriter writer();

	/**
	 * Returns the backend of the database this connection currently has selected.
	 * @return the key-value store to read and write
	 */
	KeyValueStore store();

	/**
	 * Returns the index of the currently selected database.
	 * @return the database index, {@code 0} unless {@code SELECT} changed it
	 */
	int databaseIndex();

	/**
	 * Selects the database subsequent commands operate on.
	 * @param databaseIndex the index, which must be in {@code [0, databaseCount())}
	 * @throws IllegalArgumentException if the index is out of range; callers are expected
	 * to validate against {@link #databaseCount()} first so the client receives a RESP
	 * error instead
	 */
	void databaseIndex(int databaseIndex);

	/**
	 * Returns how many databases this server exposes; valid {@code SELECT} indexes are
	 * {@code 0} to this value minus one.
	 * @return the number of databases
	 */
	int databaseCount();

	/**
	 * Returns the protocol version replies are currently encoded in.
	 * @return the negotiated RESP version
	 */
	RespVersion protocolVersion();

	/**
	 * Switches the protocol version replies are encoded in, as negotiated by
	 * {@code HELLO}. It takes effect immediately, so a handler must set it before writing
	 * the reply that announces it.
	 * @param protocolVersion the newly negotiated version
	 */
	void protocolVersion(RespVersion protocolVersion);

	/**
	 * Reports whether this connection has satisfied the server's {@link Authenticator}.
	 * On a server that requires no credentials this is {@code true} from the start.
	 * @return {@code true} if the connection may run commands that need authentication
	 */
	boolean authenticated();

	/**
	 * Offers credentials to the server's {@link Authenticator} and, if they are accepted,
	 * marks this connection authenticated. A rejection leaves the connection exactly as
	 * it was, so an already authenticated connection does not lose that by guessing
	 * wrong.
	 * @param username the user name sent, or {@link Authenticator#DEFAULT_USERNAME} when
	 * the client sent only a password
	 * @param password the password sent
	 * @return whether the credentials were accepted
	 */
	boolean authenticate(String username, String password);

	/**
	 * Returns the unique identifier of this connection, as reported by {@code CLIENT ID}
	 * and in the {@code HELLO} reply.
	 * @return the connection identifier
	 */
	long connectionId();

	/**
	 * Returns the name the client assigned to this connection.
	 * @return the client name, or {@code null} if none was set
	 */
	@Nullable String clientName();

	/**
	 * Assigns a name to this connection, as sent by {@code CLIENT SETNAME} or
	 * {@code HELLO ... SETNAME}.
	 * @param clientName the name to report back to the client
	 */
	void clientName(String clientName);

	/**
	 * Requests that the connection be closed once the reply of the current command has
	 * been flushed, as {@code QUIT} does. The current command still completes normally.
	 */
	void requestClose();

}
