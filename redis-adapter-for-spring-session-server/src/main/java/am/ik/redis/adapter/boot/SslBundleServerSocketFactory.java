package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import am.ik.redis.adapter.boot.RedisAdapterProperties.Ssl.ClientAuth;
import am.ik.redis.adapter.server.RedisAdapterServer;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslOptions;

/**
 * The {@link ServerSocketFactory} that turns a Spring Boot {@link SslBundle} into the TLS
 * seam {@link RedisAdapterServer} was built with. The core takes a socket factory and
 * nothing else; everything Spring knows about certificates stops here.
 *
 * <p>
 * Sockets are created by the bundle's own {@code SSLContext}, and each one is then given
 * the rest of what the bundle asked for: the ciphers and protocols of its
 * {@link SslOptions}, and whether a client has to present a certificate of its own. Those
 * are per-socket settings the {@code SSLContext} cannot carry, which is why this class
 * exists rather than the context's factory being passed straight through.
 *
 * <p>
 * The context is created once, as this factory is. Certificate material replaced on disk
 * therefore reaches clients when the server is restarted; following a bundle that Spring
 * Boot reloads ({@code SslBundles.addBundleUpdateHandler}) would mean rebinding the
 * listening socket, and is left for later.
 */
final class SslBundleServerSocketFactory extends ServerSocketFactory {

	private final SSLServerSocketFactory delegate;

	private final SslOptions options;

	private final ClientAuth clientAuth;

	/**
	 * Creates a factory serving the given bundle's certificate.
	 * @param bundle the certificate, key and trust material to serve
	 * @param clientAuth what to ask of a client's own certificate
	 */
	SslBundleServerSocketFactory(SslBundle bundle, ClientAuth clientAuth) {
		this.delegate = bundle.createSslContext().getServerSocketFactory();
		this.options = bundle.getOptions();
		this.clientAuth = clientAuth;
	}

	@Override
	public ServerSocket createServerSocket() throws IOException {
		return configure(this.delegate.createServerSocket());
	}

	@Override
	public ServerSocket createServerSocket(int port) throws IOException {
		return configure(this.delegate.createServerSocket(port));
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog) throws IOException {
		return configure(this.delegate.createServerSocket(port, backlog));
	}

	@Override
	public ServerSocket createServerSocket(int port, int backlog, @Nullable InetAddress ifAddress) throws IOException {
		return configure(this.delegate.createServerSocket(port, backlog, ifAddress));
	}

	/**
	 * Applies the bundle's options and the client-authentication setting to a socket
	 * before anything is accepted on it.
	 * @param socket the socket the SSL context just created
	 * @return the same socket, configured
	 * @throws IllegalStateException if the socket does not speak TLS, which would leave
	 * the adapter serving clear text under a configuration that asked for TLS
	 */
	private ServerSocket configure(ServerSocket socket) {
		if (!(socket instanceof SSLServerSocket sslSocket)) {
			throw new IllegalStateException(
					"Expected an SSLServerSocket from the SSL bundle's context but got " + socket.getClass().getName());
		}
		String[] ciphers = this.options.getCiphers();
		if (ciphers != null) {
			sslSocket.setEnabledCipherSuites(ciphers);
		}
		String[] enabledProtocols = this.options.getEnabledProtocols();
		if (enabledProtocols != null) {
			sslSocket.setEnabledProtocols(enabledProtocols);
		}
		switch (this.clientAuth) {
			case NONE -> {
			}
			case WANT -> sslSocket.setWantClientAuth(true);
			case NEED -> sslSocket.setNeedClientAuth(true);
		}
		return sslSocket;
	}

}
