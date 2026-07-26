package am.ik.redis.adapter.boot.etcd;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.etcd.EtcdException;
import am.ik.redis.adapter.etcd.EtcdKeyValueStore;
import am.ik.redis.adapter.store.KeyEventListener;
import am.ik.redis.adapter.store.KeyValueStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import am.ik.redis.adapter.boot.RedisAdapterServerAutoConfiguration;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reaching etcd over TLS, through the SSL bundle {@code redis-adapter.etcd.ssl-bundle}
 * names.
 *
 * <p>
 * A cluster holding every session of every application is not usually reachable in clear
 * text, so this is the deployment rather than an option, and it is the one part of the
 * etcd backend that is configured the way the adapter's own port is: with a Spring Boot
 * {@code SslBundle}. Both halves are asserted — the bundle gets the store onto an
 * encrypted etcd, and without it the same store cannot connect, which is what says the
 * bundle is what did it rather than a JDK that trusts everything.
 *
 * <p>
 * The certificate is the test material the adapter's own TLS tests use; it covers
 * {@code localhost} and {@code 127.0.0.1}, which is where Testcontainers publishes a
 * port.
 */
class EtcdBackendTlsTests {

	private static final GenericContainer<?> etcd = new GenericContainer<>("quay.io/coreos/etcd:v3.7.1")
		.withExposedPorts(2379)
		.withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.crt", 0644), "/tls/server.crt")
		.withCopyFileToContainer(MountableFile.forClasspathResource("tls/server.key", 0644), "/tls/server.key")
		.withCommand("etcd", "--advertise-client-urls", "https://0.0.0.0:2379", "--listen-client-urls",
				"https://0.0.0.0:2379", "--cert-file", "/tls/server.crt", "--key-file", "/tls/server.key")
		.waitingFor(Wait.forHttp("/health").forPort(2379).usingTls().allowInsecure().forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private static String endpoint;

	@BeforeAll
	static void startEncryptedEtcd() {
		etcd.start();
		assertThat(etcd.getHost()).as("the test certificate covers localhost and 127.0.0.1 only")
			.isIn("localhost", "127.0.0.1");
		endpoint = "https://" + etcd.getHost() + ":" + etcd.getMappedPort(2379);
	}

	@Test
	void theBundleGetsTheBackendOntoAnEncryptedEtcd() {
		runner().withPropertyValues("redis-adapter.etcd.ssl-bundle=etcd").run(context -> {
			KeyValueStore store = context.getBean(KeyValueStores.class).database(0);
			assertThat(store).isInstanceOf(EtcdKeyValueStore.class);
			BlockingQueue<String> events = new LinkedBlockingQueue<>();
			store.addKeyEventListener(new KeyEventListener() {
				@Override
				public void onDeleted(byte[] key) {
					events.add("deleted " + new String(key, UTF_8));
				}
			});

			store.append("session".getBytes(UTF_8), "value".getBytes(UTF_8));
			assertThat(store.exists("session".getBytes(UTF_8))).isTrue();
			store.delete("session".getBytes(UTF_8));

			// The watch is a request of its own, and a long-lived one, so it is worth
			// saying that it is encrypted too: it carries every session event.
			assertThat(events.poll(20, TimeUnit.SECONDS)).isEqualTo("deleted session");
		});
	}

	/**
	 * Without the bundle the JDK's own trust material is used, which has never heard of
	 * the test CA. The failure is what proves the bundle was doing the work in the test
	 * above.
	 */
	@Test
	void withoutTheBundleTheSameEtcdCannotBeReached() {
		runner().run(context -> {
			KeyValueStore store = context.getBean(KeyValueStores.class).database(0);

			assertThatThrownBy(() -> store.append("session".getBytes(UTF_8), "value".getBytes(UTF_8)))
				.isInstanceOf(EtcdException.class)
				.hasMessageContaining("No etcd endpoint could be reached");
		});
	}

	private static ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(
					AutoConfigurations.of(SslAutoConfiguration.class, RedisAdapterServerAutoConfiguration.class))
			.withUserConfiguration(EtcdBackendConfiguration.class)
			.withPropertyValues("redis-adapter.bind-address=127.0.0.1", "redis-adapter.port=0",
					"redis-adapter.etcd.endpoints=" + endpoint,
					// A keyspace of this run's own, so nothing an earlier run left
					// behind is read as this one's.
					"redis-adapter.etcd.key-prefix=/tls-" + UUID.randomUUID() + "/",
					"spring.ssl.bundle.pem.etcd.truststore.certificate=classpath:tls/ca.crt");
	}

}
