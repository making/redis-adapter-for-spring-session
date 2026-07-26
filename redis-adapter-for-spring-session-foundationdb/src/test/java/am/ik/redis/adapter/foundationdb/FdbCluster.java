package am.ik.redis.adapter.foundationdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The FoundationDB every test runs against: a real one, in a container.
 *
 * <p>
 * A backend is only worth anything if it works against the store it names, and almost
 * everything interesting here — that a transaction conflicts, that a versionstamped key
 * lands in commit order, that a watch fires, where the ceilings are — is FoundationDB's
 * behaviour rather than this code's. A fake would only prove that the fake agrees with
 * the assumptions, which is exactly what is in doubt.
 *
 * <h2>Why this is not the usual container fixture</h2> A FoundationDB client
 * <strong>asserts that the port it reached is the port the server advertises</strong>.
 * Point it at a remapped published port and it prints
 * {@code Assertion pkt.canonicalRemotePort == peerAddress.port failed} and then times
 * out, so Testcontainers' ordinary "expose a port and read the random mapped one" cannot
 * work. The way round it is to make the two the same number: pick a free host port at run
 * time and bind it to itself. Choosing the port at run time rather than fixing one is
 * what keeps this parallel-safe.
 *
 * <p>
 * One container serves every test class in the JVM, started on first use and left to
 * Testcontainers to remove. Tests keep out of each other's way by using a key prefix of
 * their own rather than by starting a cluster each.
 */
public final class FdbCluster {

	/**
	 * The FoundationDB to test against, the same version as the client jar: a mixed pair
	 * is untested here, and pinning is what makes a failure reproducible.
	 */
	public static final String IMAGE = "foundationdb/foundationdb:" + FoundationDbNativeClient.version();

	private static @Nullable GenericContainer<?> container;

	private static @Nullable String clusterFile;

	private FdbCluster() {
	}

	/**
	 * Returns the path of a cluster file for the running FoundationDB, starting it if
	 * this is the first call.
	 * @return the cluster file to build a store with
	 */
	public static synchronized String clusterFile() {
		String running = clusterFile;
		if (running != null) {
			return running;
		}
		FoundationDbNativeClient.ensureLoaded();
		int port = freePort();
		GenericContainer<?> started = new GenericContainer<>(IMAGE).withExposedPorts(port)
			// FDB_NETWORKING_MODE=host is what makes the server advertise 127.0.0.1
			// rather than the container's own address, which is the address the client
			// will then check the port of.
			.withEnv("FDB_NETWORKING_MODE", "host")
			.withEnv("FDB_PORT", String.valueOf(port))
			.withCreateContainerCmdModifier(command -> Objects.requireNonNull(command.getHostConfig(), "hostConfig")
				.withPortBindings(
						new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port), new ExposedPort(port))))
			.waitingFor(Wait.forListeningPorts(port))
			.withStartupTimeout(Duration.ofMinutes(2));
		started.start();
		container = started;
		configureSingleNode(started);
		clusterFile = writeClusterFile(port);
		return clusterFile;
	}

	/**
	 * Returns the cluster's own status document.
	 *
	 * <p>
	 * It is read from FoundationDB rather than counted in the adapter, because what the
	 * code appears to issue and what a cluster is actually made to commit are two
	 * different numbers — and the second one is what a session write costs. It is asked
	 * for through {@code fdbcli} inside the container rather than through the special key
	 * space, so that reading the counters is not itself one of the reads being counted.
	 * @return the status JSON, as {@code fdbcli} prints it
	 */
	public static String statusJson() {
		clusterFile();
		try {
			return Objects.requireNonNull(container, "container")
				.execInContainer("fdbcli", "--exec", "status json")
				.getStdout();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read the FoundationDB container's status", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while reading the FoundationDB container's status", e);
		}
	}

	/**
	 * Returns a description of what the tests ran against, for a performance report.
	 * @return one line naming the image and the shape of the cluster
	 */
	public static String describe() {
		return "A single-member `" + IMAGE + "` in a container, memory storage engine, over loopback; "
				+ "the client is `org.foundationdb:fdb-java:" + FoundationDbNativeClient.version() + "`.";
	}

	/**
	 * Turns the freshly started, unconfigured FoundationDB into a database. A container
	 * that has never been configured serves nothing at all, and the client would wait for
	 * it for ever rather than fail — which is the failure mode this one line prevents.
	 */
	private static void configureSingleNode(GenericContainer<?> container) {
		try {
			Container.ExecResult configured = container.execInContainer("fdbcli", "--exec",
					"configure new single memory");
			String said = configured.getStdout() + configured.getStderr();
			if (!said.contains("Database created") && !said.contains("already present")) {
				throw new IllegalStateException("Could not configure the FoundationDB container: " + said);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not configure the FoundationDB container", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while configuring the FoundationDB container", e);
		}
	}

	private static String writeClusterFile(int port) {
		try {
			Path file = Files.createTempFile("fdb-test-", ".cluster");
			file.toFile().deleteOnExit();
			Files.writeString(file, "docker:docker@127.0.0.1:" + port + "\n", StandardCharsets.UTF_8);
			return file.toString();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not write a cluster file for the FoundationDB container", e);
		}
	}

	/**
	 * Returns a port nothing is listening on, which the container will then both publish
	 * and advertise. There is the usual race between letting go of a port and binding it,
	 * and it is the price of the port having to be known before the container starts.
	 * @return a free port on the loopback address
	 */
	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not find a free port for the FoundationDB container", e);
		}
	}

}
