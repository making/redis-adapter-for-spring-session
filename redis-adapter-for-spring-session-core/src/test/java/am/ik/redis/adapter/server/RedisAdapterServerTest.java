package am.ik.redis.adapter.server;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.command.StandardCommands;
import am.ik.redis.adapter.store.FakeKeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Drives the server over a real TCP socket with hand-written RESP, covering the accept
 * loop, the per-connection request/response loop and shutdown.
 */
class RedisAdapterServerTest {

	private final FakeKeyValueStore firstDatabase = new FakeKeyValueStore();

	private final FakeKeyValueStore secondDatabase = new FakeKeyValueStore();

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.databases(List.of(this.firstDatabase, this.secondDatabase))
		.build();

	@BeforeEach
	void startServer() {
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop();
	}

	@Test
	void answersACommandSentOverASocket() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("PING");

			assertThat(client.readLine()).isEqualTo("+PONG");
		}
	}

	@Test
	void servesManyCommandsOnOneConnectionInOrder() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("PING", "one");
			client.send("PING", "two");

			assertThat(client.readLine()).isEqualTo("$3");
			assertThat(client.readLine()).isEqualTo("one");
			assertThat(client.readLine()).isEqualTo("$3");
			assertThat(client.readLine()).isEqualTo("two");
		}
	}

	/**
	 * Each connection carries its own selected database, and the databases are genuinely
	 * separate keyspaces: what one connection writes after {@code SELECT} is invisible to
	 * a connection that never selected.
	 */
	@Test
	void servesConnectionsIndependently() throws Exception {
		try (RawRedisClient first = connect(); RawRedisClient second = connect()) {
			first.send("SELECT", "1");
			assertThat(first.readLine()).isEqualTo("+OK");

			first.send("APPEND", "key", "value");
			assertThat(first.readLine()).isEqualTo(":5");

			second.send("EXISTS", "key");

			assertThat(second.readLine()).isEqualTo(":0");
		}
		assertThat(this.secondDatabase.exists("key".getBytes(UTF_8))).isTrue();
		assertThat(this.firstDatabase.exists("key".getBytes(UTF_8))).isFalse();
	}

	@Test
	void keepsTheConnectionOpenAfterAnUnknownCommand() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("FROBNICATE");
			assertThat(client.readLine()).isEqualTo("-ERR unknown command 'FROBNICATE'");

			client.send("PING");

			assertThat(client.readLine()).isEqualTo("+PONG");
		}
	}

	@Test
	void reportsAndClosesOnAMalformedRequest() throws Exception {
		try (RawRedisClient client = connect()) {
			client.sendRaw("*1\r\n+not-a-bulk-string\r\n");

			assertThat(client.readLine()).isEqualTo("-ERR Protocol error: expected '$' bulk marker but got 0x2b");
			assertThat(client.readLine()).isNull();
		}
	}

	@Test
	void closesTheConnectionAfterQuit() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("QUIT");

			assertThat(client.readLine()).isEqualTo("+OK");
			assertThat(client.readLine()).isNull();
		}
	}

	@Test
	void rejectsSelectingADatabaseTheServerDoesNotHave() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("SELECT", "2");
			assertThat(client.readLine()).isEqualTo("-ERR DB index is out of range");

			client.send("APPEND", "key", "value");

			assertThat(client.readLine()).isEqualTo(":5");
		}
		// The rejected SELECT left the connection on the database it was already using.
		assertThat(this.firstDatabase.exists("key".getBytes(UTF_8))).isTrue();
		assertThat(this.secondDatabase.exists("key".getBytes(UTF_8))).isFalse();
	}

	@Test
	void closesLiveConnectionsWhenItStops() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("PING");
			assertThat(client.readLine()).isEqualTo("+PONG");

			this.server.stop();

			assertThat(client.readLine()).isNull();
			assertThat(this.server.isRunning()).isFalse();
		}
	}

	@Test
	void leavesNoAcceptorThreadBehindWhenItStops() throws Exception {
		try (RawRedisClient client = connect()) {
			client.send("PING");
			assertThat(client.readLine()).isEqualTo("+PONG");
		}

		this.server.stop();

		assertThat(Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName))
			.doesNotContain("redis-adapter-acceptor");
	}

	@Test
	void refusesEveryCommandUntilTheClientAuthenticates() throws Exception {
		RedisAdapterServer protectedServer = protectedServer();
		try (RawRedisClient client = new RawRedisClient(protectedServer.port())) {
			client.send("PING");
			assertThat(client.readLine()).isEqualTo("-NOAUTH Authentication required.");

			client.send("AUTH", "guess");
			assertThat(client.readLine()).isEqualTo("-WRONGPASS invalid username-password pair or user is disabled.");

			client.send("PING");
			assertThat(client.readLine()).isEqualTo("-NOAUTH Authentication required.");

			client.send("AUTH", "s3cret");
			assertThat(client.readLine()).isEqualTo("+OK");

			client.send("PING");
			assertThat(client.readLine()).isEqualTo("+PONG");
		}
		finally {
			protectedServer.stop();
		}
	}

	@Test
	void authenticatingOneConnectionLeavesTheOthersLockedOut() throws Exception {
		RedisAdapterServer protectedServer = protectedServer();
		try (RawRedisClient authenticated = new RawRedisClient(protectedServer.port());
				RawRedisClient other = new RawRedisClient(protectedServer.port())) {
			authenticated.send("AUTH", "s3cret");
			assertThat(authenticated.readLine()).isEqualTo("+OK");

			other.send("PING");

			assertThat(other.readLine()).isEqualTo("-NOAUTH Authentication required.");
		}
		finally {
			protectedServer.stop();
		}
	}

	@Test
	void stopsEvenWhileAConnectionIsStuckInsideACommand() throws Exception {
		CountDownLatch running = new CountDownLatch(1);
		RedisAdapterServer blocked = RedisAdapterServer.builder()
			.host("127.0.0.1")
			.port(0)
			.store(this.firstDatabase)
			.dispatcher(dispatcherThatBlocksOn(running))
			.shutdownTimeout(Duration.ofMillis(200))
			.build();
		blocked.start();
		try (RawRedisClient client = new RawRedisClient(blocked.port())) {
			client.send("BLOCK");
			assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

			assertTimeoutPreemptively(Duration.ofSeconds(10), blocked::stop);

			assertThat(blocked.isRunning()).isFalse();
		}
		finally {
			blocked.stop();
		}
	}

	@Test
	void releasesItsPortWhenItStops() throws Exception {
		int port = this.server.port();
		this.server.stop();

		RedisAdapterServer restarted = RedisAdapterServer.builder()
			.host("127.0.0.1")
			.port(port)
			.store(this.firstDatabase)
			.build();
		restarted.start();
		try (RawRedisClient client = new RawRedisClient(port)) {
			client.send("PING");

			assertThat(client.readLine()).isEqualTo("+PONG");
		}
		finally {
			restarted.stop();
		}
	}

	@Test
	void stoppingTwiceIsHarmless() {
		this.server.stop();
		this.server.stop();

		assertThat(this.server.isRunning()).isFalse();
	}

	@Test
	void rejectsBeingStartedTwice() {
		assertThatIllegalStateException().isThrownBy(this.server::start).withMessage("server is already running");
	}

	@Test
	void hasNoPortWhileItIsStopped() {
		this.server.stop();

		assertThatIllegalStateException().isThrownBy(this.server::port).withMessage("server is not running");
	}

	@Test
	void rejectsBeingBuiltWithoutABackend() {
		RedisAdapterServer.Builder builder = RedisAdapterServer.builder();

		assertThatIllegalStateException().isThrownBy(builder::build).withMessage("a store or databases must be set");
	}

	@Test
	void rejectsBeingBuiltWithoutAnyDatabase() {
		RedisAdapterServer.Builder builder = RedisAdapterServer.builder();

		assertThatIllegalArgumentException().isThrownBy(() -> builder.databases(List.of()))
			.withMessage("at least one database is required");
	}

	private RawRedisClient connect() throws IOException {
		return new RawRedisClient(this.server.port());
	}

	/**
	 * A second, already started server that requires a password, on its own port.
	 */
	private RedisAdapterServer protectedServer() {
		RedisAdapterServer protectedServer = RedisAdapterServer.builder()
			.host("127.0.0.1")
			.port(0)
			.store(this.firstDatabase)
			.password("s3cret")
			.build();
		protectedServer.start();
		return protectedServer;
	}

	/**
	 * The standard command set plus a command that never finishes on its own, standing in
	 * for a backend call that hangs.
	 */
	private static CommandDispatcher dispatcherThatBlocksOn(CountDownLatch running) {
		CommandDispatcher.Builder builder = CommandDispatcher.builder();
		StandardCommands.registerTo(builder);
		builder.register("BLOCK", (connection, argv) -> {
			running.countDown();
			try {
				Thread.sleep(Duration.ofMinutes(5));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		return builder.build();
	}

}
