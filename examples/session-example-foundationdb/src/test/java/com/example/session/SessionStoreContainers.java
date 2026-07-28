package com.example.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * The containers an application under test can keep its sessions in.
 *
 * <p>
 * {@link #adapter()} is a new adapter every time, over the one FoundationDB they all
 * share, so two applications get an adapter each and have nothing but the cluster in
 * common. {@link #redis()} is a single Redis handed to everyone, because a single Redis
 * is how two applications share sessions when there is no adapter in the way. Which of
 * the two an application gets is a Spring profile; see
 * {@link TestcontainersConfiguration}.
 *
 * <h2>The one thing this backend asks for</h2> FoundationDB serves no HTTP API, so the
 * only way to reach it is its native client — {@code libfdb_c}, which is in no jar. A
 * deployment installs the FoundationDB client package into the image the adapter runs in;
 * here that image is built the shortest honest way, by copying the library out of the
 * FoundationDB image the cluster itself runs. Client and cluster then cannot drift apart,
 * which matters: a mixed pair of versions is not tested anywhere in this project.
 *
 * <p>
 * None of that reaches the application. It links against nothing, and talks to the
 * adapter over TCP the way it would talk to Redis.
 *
 * <p>
 * The adapter is otherwise started the way a server starts it — {@code java -jar} on a
 * plain JRE image. Maven puts the jar under {@code target/adapter/} before the tests run
 * ({@code maven-dependency-plugin} in {@code pom.xml}), so what is tested is a published
 * artifact rather than a class path assembled here.
 */
final class SessionStoreContainers {

	/**
	 * The FoundationDB to run against, and the source of the native client the adapter's
	 * image carries. Pinned rather than {@code latest} so that a failure is reproducible,
	 * and it has to be the version of the {@code fdb-java} inside the adapter jar —
	 * {@code foundationdb.version} in the repository's root {@code pom.xml}.
	 */
	static final String FOUNDATIONDB_VERSION = "7.4.6";

	static final String FOUNDATIONDB_IMAGE = "foundationdb/foundationdb:" + FOUNDATIONDB_VERSION;

	/**
	 * The Redis to compare against, pinned for the same reason.
	 */
	static final String REDIS_IMAGE = "redis:8.2-alpine";

	/**
	 * What the adapter's jar is run on, before the native client is added to it. Nothing
	 * but a JRE is needed, and it has to be a Java 25 one.
	 */
	static final String JRE_IMAGE = "eclipse-temurin:25-jre";

	/**
	 * The port both a Redis and an adapter serve the Redis protocol on.
	 */
	static final int REDIS_PORT = 6379;

	/**
	 * The port a FoundationDB server listens on and — this is the part that matters — the
	 * port it advertises to clients. A client checks that the two agree, which is why
	 * nothing here reaches the cluster through a published, remapped port: the adapters
	 * are on the same Docker network and talk to it directly.
	 */
	private static final int FOUNDATIONDB_PORT = 4500;

	/**
	 * Where the FoundationDB image keeps the cluster file it writes for itself. It names
	 * the address the server advertises, so it is read from the container rather than
	 * composed here.
	 */
	private static final String CLUSTER_FILE = "/var/fdb/fdb.cluster";

	private static final Path SERVER_JAR = Path.of("target", "adapter", "redis-adapter-server.jar");

	private static final Network NETWORK = Network.newNetwork();

	/**
	 * A JRE with the FoundationDB client library beside it, which is what the adapter
	 * needs and a stock JRE image has not got. It is tagged rather than left anonymous so
	 * that Docker's build cache makes every run after the first one instant.
	 */
	private static final ImageFromDockerfile ADAPTER_IMAGE = new ImageFromDockerfile(
			"session-example-foundationdb-adapter:" + FOUNDATIONDB_VERSION, false)
		.withFileFromString("Dockerfile", """
				FROM %s AS client
				FROM %s
				COPY --from=client /usr/lib/libfdb_c.so /usr/lib/libfdb_c.so
				""".formatted(FOUNDATIONDB_IMAGE, JRE_IMAGE));

	private static final GenericContainer<?> FOUNDATIONDB = new GenericContainer<>(FOUNDATIONDB_IMAGE)
		.withNetwork(NETWORK)
		.withExposedPorts(FOUNDATIONDB_PORT)
		.waitingFor(Wait.forListeningPort())
		.withStartupTimeout(Duration.ofMinutes(2));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT)
		.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

	private SessionStoreContainers() {
	}

	/**
	 * Returns an adapter of its own, over the FoundationDB every adapter shares.
	 *
	 * <p>
	 * It is configured the way a container platform configures it: environment variables,
	 * one per {@code redis-adapter.*} property. Where the cluster is arrives as
	 * {@code cluster-file-contents} rather than as a path, because that is the property
	 * meant for a deployment that delivers configuration rather than files — the adapter
	 * writes the file itself. The cluster is started here rather than declared as a bean
	 * of its own, so that closing one application's context cannot take the store out
	 * from under another's.
	 * @return the container, for the caller to start
	 */
	static GenericContainer<?> adapter() {
		return new GenericContainer<>(ADAPTER_IMAGE).withNetwork(NETWORK)
			.withExposedPorts(REDIS_PORT)
			.withCopyFileToContainer(MountableFile.forHostPath(serverJar()), "/opt/redis-adapter-server.jar")
			.withEnv("REDIS_ADAPTER_FOUNDATIONDB_CLUSTER_FILE_CONTENTS", clusterFileContents())
			// The driver reaches libfdb_c through JNI, which a JDK of this vintage warns
			// about and a later one will refuse outright unless it is allowed here.
			.withCommand("java", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/redis-adapter-server.jar")
			.waitingFor(Wait.forLogMessage(".*Redis adapter server listening on.*", 1))
			.withStartupTimeout(Duration.ofMinutes(2));
	}

	/**
	 * Returns the one Redis every application shares.
	 * @return the container, already running if another application asked for it first
	 */
	static GenericContainer<?> redis() {
		return REDIS;
	}

	/**
	 * Returns the cluster file of the running FoundationDB, starting and configuring it
	 * if this is the first call.
	 *
	 * <p>
	 * It is read out of the container because it names the address the server advertises
	 * to clients, which is the container's own address on the Docker network the adapters
	 * are on.
	 * @return the contents of a cluster file pointing at the running cluster
	 */
	private static synchronized String clusterFileContents() {
		if (!FOUNDATIONDB.isRunning()) {
			FOUNDATIONDB.start();
			configureSingleNode();
		}
		return execute("Could not read the FoundationDB container's cluster file", "cat", CLUSTER_FILE).strip();
	}

	/**
	 * Turns the freshly started, unconfigured FoundationDB into a database. A container
	 * that has never been configured serves nothing at all, and a client would wait for
	 * it for ever rather than fail — which is the failure mode this one line prevents.
	 */
	private static void configureSingleNode() {
		String said = execute("Could not configure the FoundationDB container", "fdbcli", "--exec",
				"configure new single memory");
		if (!said.contains("Database created") && !said.contains("already present")) {
			throw new IllegalStateException("Could not configure the FoundationDB container: " + said);
		}
	}

	private static String execute(String failure, String... command) {
		try {
			Container.ExecResult result = FOUNDATIONDB.execInContainer(command);
			return result.getStdout() + result.getStderr();
		}
		catch (IOException e) {
			throw new UncheckedIOException(failure, e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(failure, e);
		}
	}

	private static Path serverJar() {
		Path jar = SERVER_JAR.toAbsolutePath();
		if (!Files.isRegularFile(jar)) {
			throw new IllegalStateException(("%s is missing. It is copied there by Maven, so run the tests with "
					+ "./mvnw test rather than from an IDE alone, and install the adapter first with "
					+ "./mvnw install -DskipTests in the repository root.")
				.formatted(jar));
		}
		return jar;
	}

}
