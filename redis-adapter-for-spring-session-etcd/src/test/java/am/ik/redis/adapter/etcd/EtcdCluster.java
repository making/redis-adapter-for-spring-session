package am.ik.redis.adapter.etcd;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The etcd every test in this module runs against: a real one, in a container.
 *
 * <p>
 * A backend is only worth anything if it works against the store it names, and almost
 * everything interesting here — that a lease removes a key, that a watch reports what the
 * key held, that a transaction refuses a stale revision — is etcd's behaviour rather than
 * this code's. A fake would only prove that the fake agrees with the assumptions, which
 * is exactly what is in doubt.
 *
 * <p>
 * One container serves every test class in the JVM, started on first use and left to
 * Testcontainers to remove. Tests keep out of each other's way by using a key prefix of
 * their own rather than by starting an etcd each, which would cost seconds per class.
 */
final class EtcdCluster {

	/**
	 * The etcd to test against. Pinned rather than {@code latest} so that a failure is
	 * reproducible, and moved deliberately when a new etcd is released.
	 */
	static final String IMAGE = "quay.io/coreos/etcd:v3.7.1";

	private static final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(2379)
		.withCommand("etcd", "--advertise-client-urls", "http://0.0.0.0:2379", "--listen-client-urls",
				"http://0.0.0.0:2379")
		.waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private EtcdCluster() {
	}

	/**
	 * Returns the client URL of the running etcd, starting it if this is the first call.
	 * @return the endpoint to configure a store with
	 */
	static synchronized String endpoint() {
		if (!container.isRunning()) {
			container.start();
		}
		return "http://" + container.getHost() + ":" + container.getMappedPort(2379);
	}

}
