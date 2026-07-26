package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import am.ik.redis.adapter.server.RedisAdapterServer;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Covers a certificate replaced on disk while the adapter is running, which is how
 * cert-manager, Vault and every other issuer renew one.
 *
 * <p>
 * The certificate material lives in a temporary directory rather than on the class path,
 * because the point of these tests is to overwrite it: {@code server.crt} and
 * {@code server.key} are copied there, the bundle is declared {@code reload-on-update},
 * and the test then copies {@code server-rotated.crt} and {@code server-rotated.key} over
 * them. Both certificates are signed by the same test CA and carry the same subject and
 * subject alternative names — the renewal of one certificate, not a different one — so
 * what tells them apart on the wire is the serial number, and that is what is asserted.
 *
 * <p>
 * Every assertion is made on a real handshake against the running server, since which
 * certificate is served is only ever answered there.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisAdapterServerCertificateRotationTests {

	/**
	 * How long a rotation is given to reach the server. Spring Boot's file watcher is
	 * given a short quiet period below, but the watch service underneath it polls on some
	 * platforms, macOS included, so this has to allow for a poll interval rather than for
	 * a quiet period.
	 */
	private static final Duration ROTATION_TIMEOUT = Duration.ofSeconds(30);

	@TempDir
	private Path certificates;

	@BeforeEach
	void copyTheOriginalCertificateToDisk() throws IOException {
		install("server");
	}

	/**
	 * The application under test: a server serving a bundle whose files are the ones this
	 * test overwrites, and which Spring Boot is watching for it.
	 * @return the runner
	 */
	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(SslAutoConfiguration.class, RedisAdapterServerAutoConfiguration.class))
			.withUserConfiguration(TestBackendConfiguration.class)
			.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0",
					"redis-adapter.ssl.bundle=adapter",
					"spring.ssl.bundle.pem.adapter.keystore.certificate=file:"
							+ this.certificates.resolve("server.crt"),
					"spring.ssl.bundle.pem.adapter.keystore.private-key=file:"
							+ this.certificates.resolve("server.key"),
					"spring.ssl.bundle.pem.adapter.reload-on-update=true",
					"spring.ssl.bundle.watch.file.quiet-period=100ms");
	}

	/**
	 * The rotation itself: a client that connects afterwards is handed the new
	 * certificate, and the connection that was already open never notices.
	 */
	@Test
	void servesTheNewCertificateWithoutDroppingTheConnectionsAlreadyOpen() {
		runner().run(context -> {
			int port = port(context);
			assertThat(servedSerialNumber(port)).isEqualTo(serialNumberOf("server"));

			try (Client client = new Client(port)) {
				client.commands().hset("session", "opened", "before the rotation");

				install("server-rotated");

				await().atMost(ROTATION_TIMEOUT)
					.untilAsserted(
							() -> assertThat(servedSerialNumber(port)).isEqualTo(serialNumberOf("server-rotated")));
				// The client does not reconnect, so an answer here is the same TCP
				// connection, and therefore the same TLS session, as before the rotation.
				assertThat(client.commands().hget("session", "opened")).isEqualTo("before the rotation");
			}
		});
	}

	/**
	 * A half-written or otherwise unreadable certificate must not take the port with it:
	 * the material that was serving keeps serving, and the next readable rotation is
	 * still followed.
	 * @param output where the failure is reported
	 */
	@Test
	void keepsServingThePreviousCertificateWhenTheNewMaterialCannotBeRead(CapturedOutput output) {
		runner().run(context -> {
			int port = port(context);
			assertThat(servedSerialNumber(port)).isEqualTo(serialNumberOf("server"));

			Files.writeString(this.certificates.resolve("server.crt"), """
					-----BEGIN CERTIFICATE-----
					this is not a certificate
					-----END CERTIFICATE-----
					""");

			await().atMost(ROTATION_TIMEOUT)
				.untilAsserted(() -> assertThat(output)
					.contains("SSL bundle 'adapter' was updated but its certificate material could not be loaded"));
			assertThat(servedSerialNumber(port)).isEqualTo(serialNumberOf("server"));

			install("server-rotated");

			await().atMost(ROTATION_TIMEOUT)
				.untilAsserted(() -> assertThat(servedSerialNumber(port)).isEqualTo(serialNumberOf("server-rotated")));
		});
	}

	private static int port(AssertableApplicationContext context) {
		return context.getBean(RedisAdapterServer.class).port();
	}

	/**
	 * Copies one of the certificates under {@code src/test/resources/tls} over the
	 * material the server is serving, which is what an issuer renewing a certificate in
	 * place does.
	 * @param name the base name of the certificate and key to install
	 * @throws IOException if the material cannot be read or written
	 */
	private void install(String name) throws IOException {
		copy(name + ".key", "server.key");
		copy(name + ".crt", "server.crt");
	}

	private void copy(String from, String to) throws IOException {
		try (InputStream source = new ClassPathResource("tls/" + from).getInputStream()) {
			Files.copy(source, this.certificates.resolve(to), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Completes a TLS handshake with the server and reports which certificate it was
	 * served, verifying the host name on the way so that the certificate is one a real
	 * client would have accepted.
	 * @param port where the adapter listens
	 * @return the serial number of the certificate the server presented
	 * @throws IOException if the connection or the handshake fails
	 * @throws GeneralSecurityException if the client's trust material cannot be built
	 */
	private static BigInteger servedSerialNumber(int port) throws IOException, GeneralSecurityException {
		// A context of its own per handshake, so that nothing is resumed from a session
		// established before the rotation.
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, trustingTheTestCa(), null);
		try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
			SSLParameters parameters = socket.getSSLParameters();
			parameters.setEndpointIdentificationAlgorithm("HTTPS");
			socket.setSSLParameters(parameters);
			socket.startHandshake();
			return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSerialNumber();
		}
	}

	private static TrustManager[] trustingTheTestCa() throws IOException, GeneralSecurityException {
		KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
		trustStore.load(null, null);
		trustStore.setCertificateEntry("test-ca", certificate("ca"));
		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(trustStore);
		return trustManagers.getTrustManagers();
	}

	private static BigInteger serialNumberOf(String name) throws IOException, GeneralSecurityException {
		return certificate(name).getSerialNumber();
	}

	private static X509Certificate certificate(String name) throws IOException, GeneralSecurityException {
		try (InputStream content = new ClassPathResource("tls/" + name + ".crt").getInputStream()) {
			return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(content);
		}
	}

	/**
	 * A Lettuce client held open across a rotation, with reconnection switched off so
	 * that a connection dropped by the rotation would be seen as a failure rather than
	 * silently replaced.
	 */
	private static final class Client implements AutoCloseable {

		private final RedisClient client = RedisClient.create();

		private final StatefulRedisConnection<String, String> connection;

		Client(int port) throws IOException {
			this.client.setOptions(ClientOptions.builder()
				.autoReconnect(false)
				.sslOptions(SslOptions.builder().trustManager(new ClassPathResource("tls/ca.crt").getFile()).build())
				.build());
			this.connection = this.client.connect(StringCodec.UTF8,
					RedisURI.builder()
						.withHost("127.0.0.1")
						.withPort(port)
						.withSsl(true)
						.withTimeout(Duration.ofSeconds(10))
						.build());
		}

		RedisCommands<String, String> commands() {
			return this.connection.sync();
		}

		@Override
		public void close() {
			this.connection.close();
			this.client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		}

	}

}
