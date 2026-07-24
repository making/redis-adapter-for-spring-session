package am.ik.redis.adapter.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ServerSocketFactory;

import am.ik.redis.adapter.command.Authenticator;
import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.command.StandardCommands;
import am.ik.redis.adapter.pubsub.KeyspaceNotifier;
import am.ik.redis.adapter.pubsub.PubSubRegistry;
import am.ik.redis.adapter.store.KeyValueStore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Redis wire-protocol server backed by a pluggable {@link KeyValueStore}.
 *
 * <p>
 * The server binds a {@link ServerSocket}, accepts connections in a loop, and serves each
 * one on its own virtual thread. Blocking sockets plus one virtual thread per connection
 * keep the code as simple as a thread-per-connection server while scaling to the
 * connection counts a network server sees, and they need no IO framework on the
 * classpath.
 *
 * <p>
 * Sockets are created through an injected {@link ServerSocketFactory}. That is the single
 * seam TLS is added through: hand the builder an {@code SSLServerSocketFactory} and every
 * accepted connection is encrypted, with nothing else in the server changing.
 *
 * <p>
 * Typical use, with the caller keeping ownership of the backend it passes in:
 *
 * <pre>{@code
 * RedisAdapterServer server = RedisAdapterServer.builder().store(store).port(6379).build();
 * server.start();
 * // ... serve clients ...
 * server.stop();
 * }</pre>
 *
 * <p>
 * {@link #start()} both binds the port and begins accepting on it. A caller that has to
 * know the port before anything is served — a container publishing it to the rest of an
 * application, most of all when the server was asked for an ephemeral port — can split
 * the two by calling {@link #bind()} first.
 *
 * <p>
 * By default the server is open: every client that can reach the port can read and write
 * every session. Give it a password to change that:
 *
 * <pre>{@code
 * RedisAdapterServer server = RedisAdapterServer.builder().store(store).password("s3cret").build();
 * }</pre>
 *
 * Clients then authenticate exactly as they do against Redis, and until they do, every
 * command but the handshake is refused with {@code NOAUTH}. The password itself crosses
 * the network in clear text unless the server is also given an
 * {@code SSLServerSocketFactory}, so a password and TLS belong together.
 *
 * <h2>Keyspace notifications</h2> The server owns one {@link PubSubRegistry} shared by
 * all of its connections, and <strong>building</strong> it attaches a
 * {@link KeyspaceNotifier} to every backend it was given, so that a key removed by any
 * means is published as {@code __keyevent@<db>__:del} or {@code :expired}. Because the
 * listener is attached once and a backend offers no way to detach one, a backend belongs
 * to a single server.
 */
public final class RedisAdapterServer implements AutoCloseable {

	/** The port Redis listens on, and therefore the port clients expect. */
	public static final int DEFAULT_PORT = 6379;

	/** How long {@link #stop()} waits for connections before interrupting them. */
	public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

	private static final Logger logger = LoggerFactory.getLogger(RedisAdapterServer.class);

	private final @Nullable String host;

	private final int port;

	private final int backlog;

	private final ServerSocketFactory serverSocketFactory;

	private final Duration shutdownTimeout;

	private final CommandDispatcher dispatcher;

	private final Authenticator authenticator;

	private final List<KeyValueStore> databases;

	private final PubSubRegistry pubSubRegistry = new PubSubRegistry();

	private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();

	private final AtomicLong connectionIds = new AtomicLong();

	private final ReentrantLock lifecycleLock = new ReentrantLock();

	private volatile boolean running;

	private volatile @Nullable ServerSocket serverSocket;

	private @Nullable ExecutorService connectionExecutor;

	private @Nullable Thread acceptor;

	private RedisAdapterServer(Builder builder) {
		this.host = builder.host;
		this.port = builder.port;
		this.backlog = builder.backlog;
		this.serverSocketFactory = builder.serverSocketFactory;
		this.shutdownTimeout = builder.shutdownTimeout;
		this.dispatcher = (builder.dispatcher != null) ? builder.dispatcher : StandardCommands.dispatcher();
		this.authenticator = builder.authenticator;
		this.databases = List.copyOf(builder.databases);
		for (int databaseIndex = 0; databaseIndex < this.databases.size(); databaseIndex++) {
			this.databases.get(databaseIndex)
				.addKeyEventListener(new KeyspaceNotifier(this.pubSubRegistry, databaseIndex));
		}
	}

	/**
	 * Returns a builder for a server.
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Binds the listening socket without accepting anything on it yet, so that
	 * {@link #port()} is known before a single client is served. {@link #start()} does
	 * this itself when the server is not bound already; call it separately when the port
	 * has to be published to something else first — a container handing it to the rest of
	 * an application, say.
	 *
	 * <p>
	 * A client may connect to a socket that is only bound; the connection waits in the
	 * accept queue until {@link #start()} picks it up. {@link #stop()} releases the port
	 * whether or not the server ever started accepting.
	 * @throws IllegalStateException if the server is already bound
	 * @throws UncheckedIOException if the socket cannot be bound
	 */
	public void bind() {
		this.lifecycleLock.lock();
		try {
			if (this.serverSocket != null) {
				throw new IllegalStateException("server is already bound");
			}
			ServerSocket socket = bindSocket();
			this.serverSocket = socket;
			logger.info("Redis adapter server bound to {}", socket.getLocalSocketAddress());
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	/**
	 * Starts accepting connections, binding the listening socket first if {@link #bind()}
	 * has not already done so. The socket is bound before this method returns, so
	 * {@link #port()} is meaningful as soon as it does.
	 * @throws IllegalStateException if the server is already running
	 * @throws UncheckedIOException if the socket cannot be bound
	 */
	public void start() {
		this.lifecycleLock.lock();
		try {
			if (this.running) {
				throw new IllegalStateException("server is already running");
			}
			ServerSocket bound = this.serverSocket;
			ServerSocket socket = (bound != null) ? bound : bindSocket();
			ExecutorService executor = Executors
				.newThreadPerTaskExecutor(Thread.ofVirtual().name("redis-adapter-connection-", 1).factory());
			this.serverSocket = socket;
			this.connectionExecutor = executor;
			this.running = true;
			Thread thread = Thread.ofPlatform()
				.name("redis-adapter-acceptor")
				.daemon()
				.unstarted(() -> acceptLoop(socket, executor));
			this.acceptor = thread;
			thread.start();
			logger.info("Redis adapter server listening on {}", socket.getLocalSocketAddress());
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	/**
	 * Stops accepting connections, releases the port, closes the connections already
	 * established, and waits for their threads to finish. A connection still executing a
	 * command after the shutdown timeout is interrupted, so this always returns. Doing
	 * nothing if the server is neither bound nor running, so it is safe to call more than
	 * once.
	 */
	public void stop() {
		this.lifecycleLock.lock();
		try {
			if (!this.running && this.serverSocket == null) {
				return;
			}
			this.running = false;
			closeServerSocket();
			for (ClientConnection connection : this.connections) {
				connection.close();
			}
			this.connections.clear();
			awaitAcceptor();
			closeConnectionExecutor();
			this.serverSocket = null;
			this.connectionExecutor = null;
			this.acceptor = null;
			logger.info("Redis adapter server stopped");
		}
		finally {
			this.lifecycleLock.unlock();
		}
	}

	/**
	 * Stops the server; an alias for {@link #stop()} so the server can be used as a
	 * resource. The backends passed to the builder are not closed: their lifecycle
	 * belongs to whoever created them.
	 */
	@Override
	public void close() {
		stop();
	}

	/**
	 * Returns the port the server is listening on, which is the actual port when it was
	 * asked to bind an ephemeral one.
	 * @return the bound port
	 * @throws IllegalStateException if the server is not bound
	 */
	public int port() {
		ServerSocket socket = this.serverSocket;
		if (socket == null) {
			throw new IllegalStateException("server is not bound");
		}
		return socket.getLocalPort();
	}

	/**
	 * Reports whether the server is accepting connections.
	 * @return {@code true} between {@link #start()} and {@link #stop()}
	 */
	public boolean isRunning() {
		return this.running;
	}

	/**
	 * Returns how many client connections are being served at this moment.
	 * @return the number of live connections
	 */
	public int activeConnections() {
		return this.connections.size();
	}

	/**
	 * Returns how many client connections have been accepted since this server was built,
	 * including those that have since ended. The count survives a stop and start.
	 * @return the number of connections accepted so far
	 */
	public long totalConnections() {
		return this.connectionIds.get();
	}

	private ServerSocket bindSocket() {
		try {
			InetAddress bindAddress = (this.host != null) ? InetAddress.getByName(this.host) : null;
			return this.serverSocketFactory.createServerSocket(this.port, this.backlog, bindAddress);
		}
		catch (UnknownHostException e) {
			throw new IllegalArgumentException("Unknown bind address: " + this.host, e);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to bind " + this.host + ":" + this.port, e);
		}
	}

	private void acceptLoop(ServerSocket socket, ExecutorService executor) {
		while (this.running) {
			Socket client;
			try {
				client = socket.accept();
			}
			catch (IOException e) {
				if (this.running && !socket.isClosed()) {
					logger.warn("Failed to accept a connection", e);
					continue;
				}
				return;
			}
			serve(client, executor);
		}
	}

	private void serve(Socket socket, ExecutorService executor) {
		ClientConnection connection;
		try {
			connection = ClientConnection.builder()
				.socket(socket)
				.dispatcher(this.dispatcher)
				.databases(this.databases)
				.authenticator(this.authenticator)
				.pubSubRegistry(this.pubSubRegistry)
				.id(this.connectionIds.incrementAndGet())
				.build();
		}
		catch (IOException e) {
			logger.debug("Failed to set up an accepted connection: {}", e.toString());
			closeQuietly(socket);
			return;
		}
		this.connections.add(connection);
		// The server may have been stopped between the accept and the registration, in
		// which case this connection is not in the set stop() closed.
		if (!this.running) {
			this.connections.remove(connection);
			connection.close();
			return;
		}
		try {
			executor.execute(() -> {
				try {
					connection.run();
				}
				finally {
					this.connections.remove(connection);
				}
			});
		}
		catch (RejectedExecutionException e) {
			this.connections.remove(connection);
			connection.close();
		}
	}

	private void closeServerSocket() {
		ServerSocket socket = this.serverSocket;
		if (socket != null) {
			try {
				socket.close();
			}
			catch (IOException e) {
				logger.debug("Failed to close the listening socket: {}", e.toString());
			}
		}
	}

	private void awaitAcceptor() {
		Thread thread = this.acceptor;
		if (thread == null) {
			return;
		}
		try {
			thread.join(this.shutdownTimeout);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void closeConnectionExecutor() {
		ExecutorService executor = this.connectionExecutor;
		if (executor == null) {
			return;
		}
		// Every connection socket is closed by now, so the serving threads are finishing
		// rather than blocked on a read. One still inside a command is given the shutdown
		// timeout and then interrupted, so stopping never hangs.
		executor.shutdown();
		boolean terminated = false;
		try {
			terminated = executor.awaitTermination(this.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (!terminated) {
			logger.warn("Interrupting connections still running after {}", this.shutdownTimeout);
			executor.shutdownNow();
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException e) {
			logger.debug("Failed to close a socket: {}", e.toString());
		}
	}

	/**
	 * Builder for a {@link RedisAdapterServer}.
	 */
	public static final class Builder {

		private @Nullable String host;

		private int port = DEFAULT_PORT;

		private int backlog;

		private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();

		private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

		private @Nullable CommandDispatcher dispatcher;

		private Authenticator authenticator = Authenticator.open();

		private List<KeyValueStore> databases = List.of();

		private Builder() {
		}

		/**
		 * Sets the address to bind to. The default is every local address.
		 * @param host the host name or literal address
		 * @return this builder
		 */
		public Builder host(String host) {
			this.host = host;
			return this;
		}

		/**
		 * Sets the port to bind to; {@code 0} binds an ephemeral port, which
		 * {@link #port()} then reports. The default is {@value #DEFAULT_PORT}.
		 * @param port the port
		 * @return this builder
		 */
		public Builder port(int port) {
			if (port < 0 || port > 65535) {
				throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
			}
			this.port = port;
			return this;
		}

		/**
		 * Sets the maximum length of the queue of incoming connections; {@code 0} uses
		 * the platform default.
		 * @param backlog the accept queue length
		 * @return this builder
		 */
		public Builder backlog(int backlog) {
			if (backlog < 0) {
				throw new IllegalArgumentException("backlog must not be negative: " + backlog);
			}
			this.backlog = backlog;
			return this;
		}

		/**
		 * Sets the factory the listening socket is created with. Passing an
		 * {@code SSLServerSocketFactory} is all it takes to serve TLS clients.
		 * @param serverSocketFactory the factory
		 * @return this builder
		 */
		public Builder serverSocketFactory(ServerSocketFactory serverSocketFactory) {
			this.serverSocketFactory = serverSocketFactory;
			return this;
		}

		/**
		 * Sets how long {@link #stop()} lets a connection finish the command it is
		 * running before interrupting it. The default is
		 * {@link #DEFAULT_SHUTDOWN_TIMEOUT}.
		 * @param shutdownTimeout the grace period, which must be positive
		 * @return this builder
		 */
		public Builder shutdownTimeout(Duration shutdownTimeout) {
			if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
				throw new IllegalArgumentException("shutdownTimeout must be positive: " + shutdownTimeout);
			}
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Sets the dispatcher requests are routed through. The default is
		 * {@link StandardCommands#dispatcher()}.
		 * @param dispatcher the dispatcher
		 * @return this builder
		 */
		public Builder dispatcher(CommandDispatcher dispatcher) {
			this.dispatcher = dispatcher;
			return this;
		}

		/**
		 * Requires clients to authenticate with this password before they may run any
		 * command, as {@code requirepass} does in Redis. A client sending only a password
		 * authenticates as {@link Authenticator#DEFAULT_USERNAME}.
		 *
		 * <p>
		 * The password is only as private as the transport carrying it, so pair this with
		 * an {@link #serverSocketFactory(ServerSocketFactory) SSL socket factory} on any
		 * network you do not fully trust.
		 * @param password the required password, which must not be empty
		 * @return this builder
		 */
		public Builder password(String password) {
			return authenticator(Authenticator.password(password));
		}

		/**
		 * Requires clients to satisfy this check before they may run any command. The
		 * default is {@link Authenticator#open()}, which requires nothing.
		 * @param authenticator the credentials check
		 * @return this builder
		 */
		public Builder authenticator(Authenticator authenticator) {
			this.authenticator = authenticator;
			return this;
		}

		/**
		 * Serves a single database, backed by the given store. Clients then only accept
		 * {@code SELECT 0}.
		 * @param store the backend
		 * @return this builder
		 */
		public Builder store(KeyValueStore store) {
			return databases(List.of(store));
		}

		/**
		 * Serves one database per store, numbered by position, so that {@code SELECT}
		 * switches between genuinely separate keyspaces.
		 * @param databases the backend of each database, at least one
		 * @return this builder
		 */
		public Builder databases(List<KeyValueStore> databases) {
			if (databases.isEmpty()) {
				throw new IllegalArgumentException("at least one database is required");
			}
			this.databases = List.copyOf(databases);
			return this;
		}

		/**
		 * Builds the server, which does not bind anything until it is started.
		 * @return a new server
		 * @throws IllegalStateException if no backend was set
		 */
		public RedisAdapterServer build() {
			if (this.databases.isEmpty()) {
				throw new IllegalStateException("a store or databases must be set");
			}
			return new RedisAdapterServer(this);
		}

	}

}
