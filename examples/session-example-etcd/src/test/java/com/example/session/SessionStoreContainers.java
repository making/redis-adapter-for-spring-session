package com.example.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The containers an application under test can keep its sessions in.
 *
 * <p>
 * {@link #adapter()} is a new adapter every time, over the one etcd they all share, so
 * two applications get an adapter each and have nothing but etcd in common.
 * {@link #redis()} is a single Redis handed to everyone, because a single Redis is how
 * two applications share sessions when there is no adapter in the way. Which of the two
 * an application gets is a Spring profile; see {@link TestcontainersConfiguration}.
 *
 * <p>
 * The adapter is started from its runnable jar on a plain JRE image, which is what
 * {@code java -jar redis-adapter-for-spring-session-server-etcd-<version>-exec.jar} does on a
 * server. Maven puts the jar under {@code target/adapter/} before the tests run
 * ({@code maven-dependency-plugin} in {@code pom.xml}), so what is tested is a published
 * artifact rather than a class path assembled here.
 */
final class SessionStoreContainers {

	/**
	 * The etcd to run against. Pinned rather than {@code latest} so that a failure is
	 * reproducible.
	 */
	static final String ETCD_IMAGE = "quay.io/coreos/etcd:v3.7.1";

	/**
	 * The Redis to compare against, pinned for the same reason.
	 */
	static final String REDIS_IMAGE = "redis:8.2-alpine";

	/**
	 * What the adapter's jar is run on. Nothing but a JRE is needed, and it has to be a
	 * Java 25 one.
	 */
	static final String JRE_IMAGE = "eclipse-temurin:25-jre";

	/**
	 * The port both a Redis and an adapter serve the Redis protocol on.
	 */
	static final int REDIS_PORT = 6379;

	private static final String ETCD_ALIAS = "etcd";

	private static final Path SERVER_JAR = Path.of("target", "adapter", "redis-adapter-server.jar");

	private static final Network NETWORK = Network.newNetwork();

	private static final GenericContainer<?> ETCD = new GenericContainer<>(ETCD_IMAGE).withNetwork(NETWORK)
		.withNetworkAliases(ETCD_ALIAS)
		.withExposedPorts(2379)
		.withCommand("etcd", "--advertise-client-urls", "http://0.0.0.0:2379", "--listen-client-urls",
				"http://0.0.0.0:2379")
		.waitingFor(Wait.forHttp("/health").forPort(2379).forStatusCode(200))
		.withStartupTimeout(Duration.ofMinutes(2));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT)
		.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

	private SessionStoreContainers() {
	}

	/**
	 * Returns an adapter of its own, over the etcd every adapter shares.
	 *
	 * <p>
	 * It is configured the way a container platform configures it: environment variables,
	 * one per {@code redis-adapter.*} property. The etcd is started here rather than
	 * declared as a bean of its own, so that closing one application's context cannot
	 * take the store out from under another's.
	 * @return the container, for the caller to start
	 */
	static GenericContainer<?> adapter() {
		startEtcd();
		return new GenericContainer<>(JRE_IMAGE).withNetwork(NETWORK)
			.withExposedPorts(REDIS_PORT)
			.withCopyFileToContainer(MountableFile.forHostPath(serverJar()), "/opt/redis-adapter-server.jar")
			.withEnv("REDIS_ADAPTER_ETCD_ENDPOINTS", "http://" + ETCD_ALIAS + ":2379")
			.withCommand("java", "-jar", "/opt/redis-adapter-server.jar")
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

	private static synchronized void startEtcd() {
		if (!ETCD.isRunning()) {
			ETCD.start();
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
