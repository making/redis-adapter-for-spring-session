package am.ik.redis.adapter.server;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import am.ik.redis.adapter.command.Authenticator;
import am.ik.redis.adapter.command.CommandContext;
import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.protocol.RespProtocolException;
import am.ik.redis.adapter.protocol.RespReader;
import am.ik.redis.adapter.protocol.RespVersion;
import am.ik.redis.adapter.protocol.RespWriter;
import am.ik.redis.adapter.pubsub.PubSubRegistry;
import am.ik.redis.adapter.pubsub.RespSubscriber;
import am.ik.redis.adapter.pubsub.Subscriber;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One client connection, served by one virtual thread.
 *
 * <p>
 * The thread runs a strict request/response loop: read one request, dispatch it, flush
 * its reply, repeat. Because a single thread owns the connection, replies keep the order
 * of the requests that produced them without any coordination.
 *
 * <p>
 * The connection also <em>is</em> the {@link CommandContext} handlers run against: it
 * holds the negotiated protocol version, the selected database, the client name, whether
 * the client has authenticated, and the close request. That state is confined to this
 * thread; only {@link #close()} may be called from elsewhere, which is how the server
 * tears connections down.
 *
 * <p>
 * Once the client subscribes, the connection is no longer the only writer of its socket:
 * any thread that publishes a message delivers it here. The request/reply cycle therefore
 * runs under a write lock that the connection's {@link RespSubscriber} takes as well, so
 * a push can never land in the middle of a reply. A subscription outlives no connection:
 * the serving thread drops every one of them on its way out.
 *
 * <h2>Error handling</h2> A malformed frame is answered with an {@code ERR Protocol
 * error} reply and then closed, because the byte stream can no longer be resynchronized —
 * the same thing Redis does. Every other command failure is turned into an error reply by
 * the {@link CommandDispatcher} and leaves the connection usable.
 */
final class ClientConnection implements CommandContext, Runnable {

	private static final Logger logger = LoggerFactory.getLogger(ClientConnection.class);

	private final Socket socket;

	private final CommandDispatcher dispatcher;

	private final List<KeyValueStore> databases;

	private final Authenticator authenticator;

	private final PubSubRegistry pubSubRegistry;

	private final long id;

	private final RespReader reader;

	private final RespWriter writer;

	private final ReentrantLock writeLock = new ReentrantLock();

	private final RespSubscriber subscriber;

	private int databaseIndex;

	private @Nullable String clientName;

	private boolean authenticated;

	private boolean closeRequested;

	private ClientConnection(Builder builder) throws IOException {
		this.socket = Objects.requireNonNull(builder.socket, "socket must not be null");
		this.dispatcher = Objects.requireNonNull(builder.dispatcher, "dispatcher must not be null");
		this.databases = List.copyOf(Objects.requireNonNull(builder.databases, "databases must not be null"));
		this.authenticator = Objects.requireNonNull(builder.authenticator, "authenticator must not be null");
		this.pubSubRegistry = Objects.requireNonNull(builder.pubSubRegistry, "pubSubRegistry must not be null");
		this.authenticated = !this.authenticator.isRequired();
		this.id = builder.id;
		this.socket.setTcpNoDelay(true);
		this.reader = new RespReader(this.socket.getInputStream());
		this.writer = new RespWriter(new BufferedOutputStream(this.socket.getOutputStream()));
		this.subscriber = new RespSubscriber(this.writer, this.writeLock);
	}

	/**
	 * Returns a builder for a connection.
	 * @return a new builder
	 */
	static Builder builder() {
		return new Builder();
	}

	/**
	 * Serves the connection until the client disconnects, sends {@code QUIT}, breaks the
	 * protocol, or the server closes the socket. Always gives up the connection's
	 * subscriptions and closes the socket on the way out, so that nothing keeps
	 * publishing to a socket nobody is reading.
	 */
	@Override
	public void run() {
		try {
			serve();
		}
		catch (IOException e) {
			// A closed or reset socket is the normal way a connection ends.
			logger.debug("Connection {} ended: {}", this.id, e.toString());
		}
		catch (RuntimeException e) {
			logger.warn("Connection {} failed unexpectedly", this.id, e);
		}
		finally {
			this.pubSubRegistry.unsubscribeAll(this.subscriber);
			close();
		}
	}

	private void serve() throws IOException {
		logger.debug("Connection {} accepted from {}", this.id, this.socket.getRemoteSocketAddress());
		while (!this.closeRequested) {
			List<byte[]> argv;
			try {
				argv = this.reader.readCommand();
			}
			catch (RespProtocolException e) {
				logger.debug("Connection {} sent a malformed request: {}", this.id, e.toString());
				reply(() -> this.writer.writeError("ERR Protocol error: " + singleLine(e)));
				return;
			}
			if (argv == null) {
				return;
			}
			List<byte[]> request = argv;
			reply(() -> this.dispatcher.dispatch(this, request));
		}
	}

	/**
	 * Writes one complete reply and flushes it, holding the write lock throughout so that
	 * a message published from another thread waits rather than interleaving its push
	 * frame with these bytes.
	 */
	private void reply(Reply reply) throws IOException {
		this.writeLock.lock();
		try {
			reply.write();
			this.writer.flush();
		}
		finally {
			this.writeLock.unlock();
		}
	}

	/**
	 * The writing half of one request, run while the connection's output is held
	 * exclusively.
	 */
	@FunctionalInterface
	private interface Reply {

		void write() throws IOException;

	}

	/**
	 * Closes the socket, which also unblocks the serving thread if it is waiting for the
	 * next request. Safe to call from any thread and more than once.
	 */
	void close() {
		try {
			this.socket.close();
		}
		catch (IOException e) {
			logger.debug("Failed to close connection {}: {}", this.id, e.toString());
		}
	}

	@Override
	public RespWriter writer() {
		return this.writer;
	}

	@Override
	public KeyValueStore store() {
		return this.databases.get(this.databaseIndex);
	}

	@Override
	public PubSubRegistry pubSub() {
		return this.pubSubRegistry;
	}

	@Override
	public Subscriber subscriber() {
		return this.subscriber;
	}

	@Override
	public int databaseIndex() {
		return this.databaseIndex;
	}

	@Override
	public void databaseIndex(int databaseIndex) {
		if (databaseIndex < 0 || databaseIndex >= this.databases.size()) {
			throw new IllegalArgumentException("database index is out of range: " + databaseIndex);
		}
		this.databaseIndex = databaseIndex;
	}

	@Override
	public int databaseCount() {
		return this.databases.size();
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
			// Deliberately says nothing about the credentials themselves, not even which
			// half was wrong.
			logger.debug("Connection {} sent credentials that were rejected", this.id);
			return false;
		}
		this.authenticated = true;
		return true;
	}

	@Override
	public long connectionId() {
		return this.id;
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

	/**
	 * Renders a framing violation for an error reply. Error replies are line-framed, so
	 * the detail must not carry CR or LF.
	 */
	private static String singleLine(RespProtocolException e) {
		String detail = e.getMessage();
		return (detail == null) ? "invalid request" : detail.replace('\r', ' ').replace('\n', ' ');
	}

	/**
	 * Builder for a {@link ClientConnection}.
	 */
	static final class Builder {

		private @Nullable Socket socket;

		private @Nullable CommandDispatcher dispatcher;

		private @Nullable List<KeyValueStore> databases;

		private @Nullable Authenticator authenticator;

		private @Nullable PubSubRegistry pubSubRegistry;

		private long id;

		private Builder() {
		}

		/**
		 * Sets the accepted socket to serve.
		 * @param socket the client socket
		 * @return this builder
		 */
		Builder socket(Socket socket) {
			this.socket = socket;
			return this;
		}

		/**
		 * Sets the dispatcher requests are routed through.
		 * @param dispatcher the shared dispatcher
		 * @return this builder
		 */
		Builder dispatcher(CommandDispatcher dispatcher) {
			this.dispatcher = dispatcher;
			return this;
		}

		/**
		 * Sets the backends, indexed by database number.
		 * @param databases the store of each database
		 * @return this builder
		 */
		Builder databases(List<KeyValueStore> databases) {
			this.databases = databases;
			return this;
		}

		/**
		 * Sets the credentials check the connection must satisfy.
		 * @param authenticator the shared authenticator
		 * @return this builder
		 */
		Builder authenticator(Authenticator authenticator) {
			this.authenticator = authenticator;
			return this;
		}

		/**
		 * Sets the server-wide subscription registry the connection publishes into and
		 * subscribes on.
		 * @param pubSubRegistry the shared registry
		 * @return this builder
		 */
		Builder pubSubRegistry(PubSubRegistry pubSubRegistry) {
			this.pubSubRegistry = pubSubRegistry;
			return this;
		}

		/**
		 * Sets the identifier reported by {@code CLIENT ID} and {@code HELLO}.
		 * @param id the connection identifier
		 * @return this builder
		 */
		Builder id(long id) {
			this.id = id;
			return this;
		}

		/**
		 * Builds the connection, which opens its streams.
		 * @return a new connection, ready to be run
		 * @throws IOException if the socket's streams are not available
		 */
		ClientConnection build() throws IOException {
			return new ClientConnection(this);
		}

	}

}
