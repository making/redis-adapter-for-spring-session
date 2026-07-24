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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslOptions;

/**
 * The {@link ServerSocketFactory} that turns a Spring Boot {@link SslBundle} into the TLS
 * seam {@link RedisAdapterServer} was built with. The core takes a socket factory and
 * nothing else; everything Spring knows about certificates stops here.
 *
 * <p>
 * Sockets are created by a {@link RotatableSslContext} built from the bundle, and each
 * one is then given the rest of what the bundle asked for: the ciphers and protocols of
 * its {@link SslOptions}, and whether a client has to present a certificate of its own.
 * Those are per-socket settings an {@code SSLContext} cannot carry, which is why this
 * class exists rather than the context's factory being passed straight through.
 *
 * <p>
 * Certificate material replaced on disk reaches clients without a restart. Spring Boot
 * reloads a bundle declared {@code reload-on-update} and reports it through
 * {@link SslBundles#addBundleUpdateHandler}; {@link #rotate(SslBundle)} is what that
 * handler calls, and it never fails outwards — material that cannot be read leaves the
 * certificate that was serving in place, because a port serving a certificate that is
 * about to expire is worth more than a port serving nothing.
 */
final class SslBundleServerSocketFactory extends ServerSocketFactory {

	private static final Logger logger = LoggerFactory.getLogger(SslBundleServerSocketFactory.class);

	private final String bundleName;

	private final RotatableSslContext sslContext;

	private final SSLServerSocketFactory delegate;

	private final ClientAuth clientAuth;

	private volatile SslOptions options;

	private SslBundleServerSocketFactory(Builder builder) {
		String bundleName = builder.bundleName;
		SslBundle bundle = builder.bundle;
		if (bundleName == null || bundle == null) {
			throw new IllegalStateException("a bundle and its name must be set");
		}
		this.bundleName = bundleName;
		this.clientAuth = builder.clientAuth;
		this.sslContext = new RotatableSslContext(bundle);
		this.delegate = this.sslContext.serverSocketFactory();
		this.options = bundle.getOptions();
	}

	/**
	 * Returns a builder for a factory.
	 * @return a new builder
	 */
	static Builder builder() {
		return new Builder();
	}

	/**
	 * Serves the given bundle's certificate to every client that connects from now on,
	 * leaving the connections already established alone. Called by Spring Boot when it
	 * notices the material behind a {@code reload-on-update} bundle has changed.
	 * @param bundle the bundle as it now reads on disk
	 */
	void rotate(SslBundle bundle) {
		try {
			this.sslContext.rotate(bundle);
			this.options = bundle.getOptions();
			logger.info("SSL bundle '{}' was rotated; clients connecting from now on are served its new certificate",
					this.bundleName);
		}
		catch (RuntimeException e) {
			// Throwing here would only reach Spring Boot's watcher thread. The port keeps
			// serving what it was serving, which is the one outcome an operator can still
			// recover from by fixing the material on disk.
			logger.error("SSL bundle '{}' was updated but its certificate material could not be loaded; "
					+ "the certificate served until now is still being served", this.bundleName, e);
		}
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
		SslOptions options = this.options;
		String[] ciphers = options.getCiphers();
		if (ciphers != null) {
			sslSocket.setEnabledCipherSuites(ciphers);
		}
		String[] enabledProtocols = options.getEnabledProtocols();
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

	/**
	 * Builder for an {@link SslBundleServerSocketFactory}.
	 */
	static final class Builder {

		private @Nullable String bundleName;

		private @Nullable SslBundle bundle;

		private ClientAuth clientAuth = ClientAuth.NONE;

		private Builder() {
		}

		/**
		 * Sets the name the bundle is configured under, which is what a rotation is
		 * reported against.
		 * @param bundleName the name of the SSL bundle
		 * @return this builder
		 */
		Builder bundleName(String bundleName) {
			this.bundleName = bundleName;
			return this;
		}

		/**
		 * Sets the certificate, key and trust material to serve.
		 * @param bundle the SSL bundle
		 * @return this builder
		 */
		Builder bundle(SslBundle bundle) {
			this.bundle = bundle;
			return this;
		}

		/**
		 * Sets what to ask of a client's own certificate. The default is
		 * {@link ClientAuth#NONE}.
		 * @param clientAuth the client authentication mode
		 * @return this builder
		 */
		Builder clientAuth(ClientAuth clientAuth) {
			this.clientAuth = clientAuth;
			return this;
		}

		/**
		 * Builds the factory, reading the bundle's material as it does.
		 * @return a new factory
		 * @throws IllegalStateException if no bundle or no bundle name was set
		 */
		SslBundleServerSocketFactory build() {
			return new SslBundleServerSocketFactory(this);
		}

	}

}
