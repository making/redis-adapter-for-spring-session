package am.ik.redis.adapter.boot;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslManagerBundle;

/**
 * An {@link SSLContext} whose certificate material can be replaced while it is in use, so
 * that a certificate rotated on disk reaches clients without the adapter being restarted.
 *
 * <p>
 * A listening {@link javax.net.ssl.SSLServerSocket} keeps the context it was created from
 * for as long as it is bound, and hands it to every connection it accepts. Following a
 * rotation by building a second context would therefore mean binding a second listening
 * socket, which leaves the port unbound for as long as the changeover takes. Nothing has
 * to be rebound if the context itself is the thing that changes, and that is what this
 * class is: the context is created once, over key and trust managers that resolve the
 * material afresh for every handshake.
 *
 * <p>
 * The consequences follow from where the material is read. A connection that is already
 * established has completed its handshake and is never asked again, so it runs to its end
 * on the certificate it was given; the next client to connect gets whatever
 * {@link #rotate(SslBundle)} last installed. Nothing is torn down, and no client is ever
 * refused because a port was between sockets.
 *
 * <p>
 * A rotation is all or nothing: the new key and trust material are both read before
 * either is installed, so a bundle that has only half arrived on disk leaves the previous
 * material serving rather than a mismatched pair of them.
 */
final class RotatableSslContext {

	private final RotatingKeyManager keyManager;

	private final RotatingTrustManager trustManager;

	private final SSLContext sslContext;

	/**
	 * Creates a context serving the given bundle's material.
	 * @param bundle the certificate, key and trust material to start with
	 * @throws IllegalStateException if the bundle holds no X.509 material, or if its
	 * protocol is one this JVM does not support
	 */
	RotatableSslContext(SslBundle bundle) {
		SslManagerBundle managers = bundle.getManagers();
		this.keyManager = new RotatingKeyManager(keyManagerOf(managers));
		this.trustManager = new RotatingTrustManager(trustManagerOf(managers));
		this.sslContext = createSslContext(bundle.getProtocol());
	}

	/**
	 * Returns the factory the listening socket is created from. Every socket it creates
	 * follows this context, rotations included.
	 * @return the server socket factory of this context
	 */
	SSLServerSocketFactory serverSocketFactory() {
		return this.sslContext.getServerSocketFactory();
	}

	/**
	 * Replaces the material handed to the handshakes that follow. Handshakes already
	 * completed are unaffected.
	 * @param bundle the material to serve from now on
	 * @throws RuntimeException if the material cannot be read, in which case nothing is
	 * replaced and the previous material keeps serving
	 */
	void rotate(SslBundle bundle) {
		SslManagerBundle managers = bundle.getManagers();
		X509KeyManager keyManager = keyManagerOf(managers);
		X509TrustManager trustManager = trustManagerOf(managers);
		this.keyManager.set(keyManager);
		this.trustManager.set(trustManager);
	}

	private SSLContext createSslContext(String protocol) {
		try {
			SSLContext sslContext = SSLContext.getInstance(protocol);
			sslContext.init(new KeyManager[] { this.keyManager }, new TrustManager[] { this.trustManager }, null);
			return sslContext;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Could not create an SSL context for protocol '" + protocol + "'", e);
		}
	}

	private static X509KeyManager keyManagerOf(SslManagerBundle managers) {
		for (KeyManager manager : managers.getKeyManagers()) {
			if (manager instanceof X509KeyManager x509KeyManager) {
				return x509KeyManager;
			}
		}
		throw new IllegalStateException("The SSL bundle has no X.509 key manager, so it names no certificate to serve");
	}

	private static X509TrustManager trustManagerOf(SslManagerBundle managers) {
		for (TrustManager manager : managers.getTrustManagers()) {
			if (manager instanceof X509TrustManager x509TrustManager) {
				return x509TrustManager;
			}
		}
		throw new IllegalStateException(
				"The SSL bundle has no X.509 trust manager, so client certificates could not be verified");
	}

	/**
	 * The key manager the context is initialised with, forwarding every call to whichever
	 * manager was installed last.
	 *
	 * <p>
	 * It extends {@link X509ExtendedKeyManager} whatever the installed manager is, so
	 * that the JDK never has to wrap it. A wrapped manager is one the JDK cannot pass the
	 * connection to, and it is the connection that carries the server name a client asked
	 * for; the delegation below passes it on wherever the installed manager can take it.
	 */
	private static final class RotatingKeyManager extends X509ExtendedKeyManager {

		private volatile X509KeyManager delegate;

		RotatingKeyManager(X509KeyManager delegate) {
			this.delegate = delegate;
		}

		void set(X509KeyManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public String @Nullable [] getClientAliases(String keyType, Principal @Nullable [] issuers) {
			return this.delegate.getClientAliases(keyType, issuers);
		}

		@Override
		public @Nullable String chooseClientAlias(String[] keyType, Principal @Nullable [] issuers,
				@Nullable Socket socket) {
			return this.delegate.chooseClientAlias(keyType, issuers, socket);
		}

		@Override
		public String @Nullable [] getServerAliases(String keyType, Principal @Nullable [] issuers) {
			return this.delegate.getServerAliases(keyType, issuers);
		}

		@Override
		public @Nullable String chooseServerAlias(String keyType, Principal @Nullable [] issuers,
				@Nullable Socket socket) {
			return this.delegate.chooseServerAlias(keyType, issuers, socket);
		}

		@Override
		public X509Certificate @Nullable [] getCertificateChain(String alias) {
			return this.delegate.getCertificateChain(alias);
		}

		@Override
		public @Nullable PrivateKey getPrivateKey(String alias) {
			return this.delegate.getPrivateKey(alias);
		}

		@Override
		public @Nullable String chooseEngineClientAlias(String[] keyType, Principal @Nullable [] issuers,
				SSLEngine engine) {
			X509KeyManager delegate = this.delegate;
			return (delegate instanceof X509ExtendedKeyManager extended)
					? extended.chooseEngineClientAlias(keyType, issuers, engine)
					: delegate.chooseClientAlias(keyType, issuers, null);
		}

		@Override
		public @Nullable String chooseEngineServerAlias(String keyType, Principal @Nullable [] issuers,
				SSLEngine engine) {
			X509KeyManager delegate = this.delegate;
			return (delegate instanceof X509ExtendedKeyManager extended)
					? extended.chooseEngineServerAlias(keyType, issuers, engine)
					: delegate.chooseServerAlias(keyType, issuers, null);
		}

	}

	/**
	 * The trust manager the context is initialised with, forwarding every call to
	 * whichever manager was installed last, so that the certificate authority a client is
	 * verified against rotates with the rest of the bundle.
	 */
	private static final class RotatingTrustManager extends X509ExtendedTrustManager {

		private volatile X509TrustManager delegate;

		RotatingTrustManager(X509TrustManager delegate) {
			this.delegate = delegate;
		}

		void set(X509TrustManager delegate) {
			this.delegate = delegate;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			this.delegate.checkClientTrusted(chain, authType);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			this.delegate.checkServerTrusted(chain, authType);
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return this.delegate.getAcceptedIssuers();
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			X509TrustManager delegate = this.delegate;
			if (delegate instanceof X509ExtendedTrustManager extended) {
				extended.checkClientTrusted(chain, authType, socket);
			}
			else {
				delegate.checkClientTrusted(chain, authType);
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
				throws CertificateException {
			X509TrustManager delegate = this.delegate;
			if (delegate instanceof X509ExtendedTrustManager extended) {
				extended.checkServerTrusted(chain, authType, socket);
			}
			else {
				delegate.checkServerTrusted(chain, authType);
			}
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			X509TrustManager delegate = this.delegate;
			if (delegate instanceof X509ExtendedTrustManager extended) {
				extended.checkClientTrusted(chain, authType, engine);
			}
			else {
				delegate.checkClientTrusted(chain, authType);
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
				throws CertificateException {
			X509TrustManager delegate = this.delegate;
			if (delegate instanceof X509ExtendedTrustManager extended) {
				extended.checkServerTrusted(chain, authType, engine);
			}
			else {
				delegate.checkServerTrusted(chain, authType);
			}
		}

	}

}
