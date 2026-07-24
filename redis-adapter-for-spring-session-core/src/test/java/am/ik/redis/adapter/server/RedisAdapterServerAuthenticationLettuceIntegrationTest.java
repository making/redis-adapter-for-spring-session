package am.ik.redis.adapter.server;

import java.time.Duration;
import java.util.List;

import am.ik.redis.adapter.command.CommandDispatcher;
import am.ik.redis.adapter.command.StandardCommands;
import am.ik.redis.adapter.store.StubKeyValueStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a password-protected server with a real Redis client, which is what proves that
 * the credentials a client is configured with reach the adapter in the form it expects.
 */
class RedisAdapterServerAuthenticationLettuceIntegrationTest {

	private static final String PASSWORD = "s3cret";

	private final RecordingServerSocketFactory serverSocketFactory = new RecordingServerSocketFactory();

	private final CommandDispatcher dispatcher = StandardCommands.dispatcher();

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.serverSocketFactory(this.serverSocketFactory)
		.dispatcher(this.dispatcher)
		.password(PASSWORD)
		.databases(List.of(new StubKeyValueStore("db0")))
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
	void aClientConfiguredWithThePasswordIsServed() {
		try (StatefulRedisConnection<String, String> connection = connect(
				redisUri().withPassword(password()).build())) {
			assertThat(connection.sync().ping()).isEqualTo("PONG");
		}
	}

	@Test
	void aClientConfiguredWithThePasswordNeverSeesAnError() {
		try (StatefulRedisConnection<String, String> connection = connect(
				redisUri().withPassword(password()).build())) {
			connection.sync().ping();
		}
		this.client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		this.server.stop();

		List<String> commands = this.serverSocketFactory.commandNames();
		System.out.println("Commands an authenticating client sent: " + commands);
		assertThat(commands.stream().filter(command -> !this.dispatcher.supports(command)).toList()).isEmpty();
		assertThat(this.serverSocketFactory.errorReplies()).isEmpty();
	}

	@Test
	void aClientWithTheWrongPasswordIsRejected() {
		RedisURI uri = redisUri().withPassword("guess".toCharArray()).build();

		assertThatThrownBy(() -> connect(uri)).isInstanceOf(RedisConnectionException.class)
			.rootCause()
			.hasMessage("WRONGPASS invalid username-password pair or user is disabled.");
	}

	@Test
	void aClientWithoutAPasswordIsRejected() {
		RedisURI uri = redisUri().build();

		assertThatThrownBy(() -> connect(uri)).isInstanceOf(RedisConnectionException.class);
		assertThat(this.serverSocketFactory.errorReplies()).isNotEmpty().allMatch(error -> error.startsWith("-NOAUTH"));
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

	private static char[] password() {
		return PASSWORD.toCharArray();
	}

}
