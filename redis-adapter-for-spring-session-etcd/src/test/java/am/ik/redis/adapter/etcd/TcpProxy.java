package am.ik.redis.adapter.etcd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A TCP proxy in front of etcd, so that a test can take a member away and give it back.
 *
 * <p>
 * The interesting failures of this backend are the ones where etcd is fine and the
 * connection is not: a watch that has to reconnect and pick up what it missed, a cluster
 * member that stops serving while another still does. Restarting the container would
 * prove neither — it takes etcd's data and its revisions with it, and it cannot be done
 * to one member of two. Standing in front of etcd can: the store talks to the proxy, the
 * test decides whether the proxy carries anything, and etcd never notices.
 *
 * <p>
 * A proxy in {@link #blackhole(boolean) blackhole} accepts a connection and closes it,
 * which is what a member that has stopped serving looks like to a client: connecting
 * works, reading does not.
 */
final class TcpProxy implements AutoCloseable {

	private final ServerSocket listener;

	private final String targetHost;

	private final int targetPort;

	private final List<Socket> connections = Collections.synchronizedList(new ArrayList<>());

	private volatile boolean blackhole;

	private volatile boolean closed;

	private TcpProxy(URI target) throws IOException {
		this.targetHost = target.getHost();
		this.targetPort = target.getPort();
		this.listener = new ServerSocket();
		this.listener.setReuseAddress(true);
		this.listener.bind(new InetSocketAddress("127.0.0.1", 0));
		Thread.ofVirtual().name("etcd-proxy-accept").start(this::accept);
	}

	/**
	 * Starts a proxy in front of an etcd endpoint.
	 * @param endpoint the endpoint to forward to
	 * @return the running proxy
	 */
	static TcpProxy to(String endpoint) {
		try {
			return new TcpProxy(URI.create(endpoint));
		}
		catch (IOException e) {
			throw new IllegalStateException("Could not start a proxy in front of " + endpoint, e);
		}
	}

	/**
	 * Returns the endpoint a store is pointed at to go through this proxy.
	 * @return the proxy's own client URL
	 */
	String endpoint() {
		return "http://127.0.0.1:" + this.listener.getLocalPort();
	}

	/**
	 * Stops or resumes carrying traffic. While blackholed, every connection is accepted
	 * and closed at once, and the connections already open are cut, so nothing this proxy
	 * is in front of can be reached through it.
	 * @param blackhole whether to swallow connections
	 */
	void blackhole(boolean blackhole) {
		this.blackhole = blackhole;
		if (blackhole) {
			cutConnections();
		}
	}

	/**
	 * Closes every connection open through the proxy, which is what a client sees when a
	 * member goes away mid-request.
	 */
	void cutConnections() {
		synchronized (this.connections) {
			for (Socket connection : this.connections) {
				closeQuietly(connection);
			}
			this.connections.clear();
		}
	}

	private void accept() {
		while (!this.closed) {
			Socket incoming;
			try {
				incoming = this.listener.accept();
			}
			catch (IOException e) {
				return; // the proxy was closed
			}
			if (this.blackhole) {
				closeQuietly(incoming);
				continue;
			}
			try {
				Socket outgoing = new Socket(this.targetHost, this.targetPort);
				this.connections.add(incoming);
				this.connections.add(outgoing);
				pump(incoming, outgoing);
				pump(outgoing, incoming);
			}
			catch (IOException e) {
				closeQuietly(incoming);
			}
		}
	}

	private void pump(Socket from, Socket to) {
		Thread.ofVirtual().name("etcd-proxy-pump").start(() -> {
			byte[] buffer = new byte[8192];
			try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
				int read;
				while ((read = in.read(buffer)) >= 0) {
					out.write(buffer, 0, read);
					out.flush();
				}
			}
			catch (IOException e) {
				// The connection was cut, by this test or by either end. Both sides go.
			}
			finally {
				closeQuietly(from);
				closeQuietly(to);
			}
		});
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException e) {
			// Nothing useful to do while tearing a connection down.
		}
	}

	@Override
	public void close() {
		this.closed = true;
		cutConnections();
		try {
			this.listener.close();
		}
		catch (IOException e) {
			// Closing the listener is the last thing this proxy does.
		}
	}

}
