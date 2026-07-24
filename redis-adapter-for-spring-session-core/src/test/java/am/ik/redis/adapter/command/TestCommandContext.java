package am.ik.redis.adapter.command;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;

/**
 * A {@link CommandContext} that collects replies in memory, so command handlers can be
 * tested down to the exact bytes they put on the wire without a socket.
 */
final class TestCommandContext implements CommandContext {

	private final ByteArrayOutputStream replies = new ByteArrayOutputStream();

	private final RespWriter writer = new RespWriter(this.replies);

	private @Nullable KeyValueStore store;

	private int databaseIndex;

	private int databaseCount = 2;

	private long connectionId = 42;

	private Authenticator authenticator = Authenticator.open();

	private boolean authenticated = true;

	private @Nullable String clientName;

	private boolean closeRequested;

	/**
	 * Returns everything written so far, decoded byte for byte.
	 * @return the reply bytes as Latin-1 text
	 */
	String replies() {
		return this.replies.toString(StandardCharsets.ISO_8859_1);
	}

	/**
	 * Discards everything written so far, so that a test can set a key up with one
	 * command and then assert only on the reply of the next.
	 * @return this context
	 */
	TestCommandContext reset() {
		this.replies.reset();
		return this;
	}

	/**
	 * Reports whether the connection was asked to close.
	 * @return {@code true} once a handler called {@link #requestClose()}
	 */
	boolean isCloseRequested() {
		return this.closeRequested;
	}

	/**
	 * Sets the backend commands run against. Without one, {@link #store()} fails, so a
	 * test that does not set it cannot silently pass a handler that touches the backend.
	 * @param store the backend
	 * @return this context
	 */
	TestCommandContext store(KeyValueStore store) {
		this.store = store;
		return this;
	}

	/**
	 * Sets how many databases the fake server exposes.
	 * @param databaseCount the number of databases
	 * @return this context
	 */
	TestCommandContext databaseCount(int databaseCount) {
		this.databaseCount = databaseCount;
		return this;
	}

	/**
	 * Sets the identifier reported by {@code CLIENT ID} and {@code HELLO}.
	 * @param connectionId the connection identifier
	 * @return this context
	 */
	TestCommandContext connectionId(long connectionId) {
		this.connectionId = connectionId;
		return this;
	}

	/**
	 * Sets the credentials check, starting the connection unauthenticated if the
	 * authenticator requires anything, exactly as a real connection does.
	 * @param authenticator the credentials check
	 * @return this context
	 */
	TestCommandContext authenticator(Authenticator authenticator) {
		this.authenticator = authenticator;
		this.authenticated = !authenticator.isRequired();
		return this;
	}

	/**
	 * Builds an argument vector out of ASCII arguments.
	 * @param arguments the command name followed by its arguments
	 * @return the argument vector
	 */
	static List<byte[]> argv(String... arguments) {
		List<byte[]> argv = new ArrayList<>(arguments.length);
		for (String argument : arguments) {
			argv.add(argument.getBytes(StandardCharsets.UTF_8));
		}
		return argv;
	}

	@Override
	public RespWriter writer() {
		return this.writer;
	}

	@Override
	public KeyValueStore store() {
		KeyValueStore store = this.store;
		if (store == null) {
			throw new UnsupportedOperationException("this context has no backend");
		}
		return store;
	}

	@Override
	public int databaseIndex() {
		return this.databaseIndex;
	}

	@Override
	public void databaseIndex(int databaseIndex) {
		if (databaseIndex < 0 || databaseIndex >= this.databaseCount) {
			throw new IllegalArgumentException("database index is out of range: " + databaseIndex);
		}
		this.databaseIndex = databaseIndex;
	}

	@Override
	public int databaseCount() {
		return this.databaseCount;
	}

	@Override
	public RespVersion protocolVersion() {
		return this.writer.protocolVersion();
	}

	@Override
	public void protocolVersion(RespVersion protocolVersion) {
		this.writer.protocolVersion(protocolVersion);
	}

	@Override
	public boolean authenticated() {
		return this.authenticated;
	}

	@Override
	public boolean authenticate(String username, String password) {
		if (!this.authenticator.authenticate(username, password)) {
			return false;
		}
		this.authenticated = true;
		return true;
	}

	@Override
	public long connectionId() {
		return this.connectionId;
	}

	@Override
	public @Nullable String clientName() {
		return this.clientName;
	}

	@Override
	public void clientName(String clientName) {
		this.clientName = clientName;
	}

	@Override
	public void requestClose() {
		this.closeRequested = true;
	}

}
