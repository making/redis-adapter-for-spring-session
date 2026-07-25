package am.ik.redis.adapter.boot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The etcd the performance harness measures against: one container, started on first use
 * and shared by every case in the JVM.
 *
 * <p>
 * Stock settings on purpose. Every number taken here is bounded by this container's
 * fsync, so a tuned etcd would answer faster and a busy three-member cluster over a real
 * network would answer slower — which is why the results record what this was rather than
 * presenting the numbers as a property of the backend. Nothing about the container is
 * clever: no tmpfs data directory, no relaxed commit interval, one member.
 */
final class PerformanceEtcd {

	/** The etcd every test in this repository runs against, pinned to one version. */
	static final String IMAGE = "quay.io/coreos/etcd:v3.7.1";

	private static final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(2379)
		.withCommand("etcd", "--advertise-client-urls", "http://0.0.0.0:2379", "--listen-client-urls",
				"http://0.0.0.0:2379")
		.waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private PerformanceEtcd() {
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

	/**
	 * Returns what etcd says its version is, so the results say which etcd they are of
	 * rather than which image was asked for.
	 * @return etcd's own {@code /version} answer
	 */
	static String version() {
		HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/version")).GET().build();
		try {
			return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("could not ask etcd its version", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while asking etcd its version", ex);
		}
	}

	/**
	 * Returns how the machine and the etcd should be described beside the numbers.
	 * @return one markdown line naming what took the measurements
	 */
	static String describe() {
		Runtime runtime = Runtime.getRuntime();
		return "Measured on %s %s, %d available processors, JVM %s, against %s in a container (single member, "
			.formatted(System.getProperty("os.name"), System.getProperty("os.arch"), runtime.availableProcessors(),
					Runtime.version(), IMAGE)
				+ "default flags, data directory on the container's own filesystem). etcd reports " + version() + ".";
	}

}
