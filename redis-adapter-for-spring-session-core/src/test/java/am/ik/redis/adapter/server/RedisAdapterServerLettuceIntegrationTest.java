package am.ik.redis.adapter.server;

import java.time.Duration;
import java.util.List;

import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.command.StandardCommands;
import am.ik.redis.adapter.store.FakeKeyValueStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.protocol.ProtocolVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the server with a real Redis client, which is the only way to know that the
 * handshake the adapter answers is the handshake clients actually perform.
 */
class RedisAdapterServerLettuceIntegrationTest {

	/** The identity map as the first connection of a fresh server receives it. */
	private static final String RESP3_IDENTITY = """
			%7\r
			$6\r
			server\r
			$5\r
			redis\r
			$7\r
			version\r
			$5\r
			7.4.0\r
			$5\r
			proto\r
			:3\r
			$2\r
			id\r
			:1\r
			$4\r
			mode\r
			$10\r
			standalone\r
			$4\r
			role\r
			$6\r
			master\r
			$7\r
			modules\r
			*0\r
			""";

	private final RecordingServerSocketFactory serverSocketFactory = new RecordingServerSocketFactory();

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.serverSocketFactory(this.serverSocketFactory)
		.dispatcher(this.dispatcher)
		.databases(List.of(new FakeKeyValueStore(), new FakeKeyValueStore()))
		.build();

	private final RedisClient client = RedisClient.create();

	@BeforeEach
	void startServer() {
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		this.server.stop();
	}

	@Test
	void pingIsAnsweredOverTheDefaultHandshake() {
		try (StatefulRedisConnection<String, String> connection = connect(redisUri().build())) {
			assertThat(connection.sync().ping()).isEqualTo("PONG");
		}
	}

	@Test
	void theDefaultHandshakeNegotiatesResp3() {
		try (StatefulRedisConnection<String, String> connection = connect(redisUri().build())) {
			connection.sync().ping();
		}

		assertThat(this.serverSocketFactory.commandNames()).startsWith("HELLO");
		assertThat(this.serverSocketFactory.replies()).startsWith(RESP3_IDENTITY);
	}

	/**
	 * A client pinned to RESP2 skips {@code HELLO} entirely, so a connection has to
	 * default to RESP2 rather than wait to be told which protocol to speak.
	 */
	@Test
	void aClientPinnedToResp2IsServedWithoutNegotiating() {
		this.client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());

		try (StatefulRedisConnection<String, String> connection = connect(redisUri().build())) {
			assertThat(connection.sync().ping()).isEqualTo("PONG");
		}

		assertThat(this.serverSocketFactory.commandNames()).startsWith("PING").doesNotContain("HELLO");
		assertThat(this.serverSocketFactory.replies()).startsWith("+PONG\r\n");
	}

	@Test
	void theConnectionIdentifierIsReportedBackToTheClient() {
		try (StatefulRedisConnection<String, String> connection = connect(redisUri().build())) {
			assertThat(connection.sync().clientId()).isEqualTo(1L);
		}
	}

	@Test
	void theClientNameSurvivesTheHandshake() {
		try (StatefulRedisConnection<String, String> connection = connect(
				redisUri().withClientName("adapter-test").build())) {
			assertThat(connection.sync().clientGetname()).isEqualTo("adapter-test");
		}
	}

	@Test
	void aClientCanSelectAnotherDatabaseWhileConnecting() {
		try (StatefulRedisConnection<String, String> connection = connect(redisUri().withDatabase(1).build())) {
			assertThat(connection.sync().ping()).isEqualTo("PONG");
		}

		assertThat(this.serverSocketFactory.commandNames()).contains("SELECT");
	}

	@Test
	void aClientAskingForADatabaseTheServerDoesNotHaveIsRejected() {
		RedisURI uri = redisUri().withDatabase(5).build();

		assertThatThrownBy(() -> connect(uri)).isInstanceOf(RedisConnectionException.class)
			.rootCause()
			.hasMessage("ERR DB index is out of range");
	}

	@Test
	void everyCommandTheClientSendsIsImplementedAndNeverAnsweredWithAnError() {
		try (StatefulRedisConnection<String, String> connection = connect(
				redisUri().withClientName("adapter-test").withDatabase(1).build())) {
			connection.sync().ping();
			connection.sync().clientId();
		}
		this.client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		this.server.stop();

		List<String> commands = this.serverSocketFactory.commandNames();
		System.out.println("Commands the client sent: " + commands);
		assertThat(commands).isNotEmpty();
		assertThat(commands.stream().filter(command -> !this.dispatcher.supports(command)).toList()).isEmpty();
		assertThat(this.serverSocketFactory.errorReplies()).isEmpty();
	}

	private StatefulRedisConnection<String, String> connect(RedisURI uri) {
		return this.client.connect(StringCodec.UTF8, uri);
	}

	private RedisURI.Builder redisUri() {
		return RedisURI.builder()
			.withHost("127.0.0.1")
			.withPort(this.server.port())
			.withTimeout(Duration.ofSeconds(10));
	}

}
