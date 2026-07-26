package am.ik.redis.adapter.boot.inmemory;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import am.ik.redis.adapter.boot.KeyValueStores;
import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.server.RedisAdapterServer;
import am.ik.redis.adapter.store.KeyValueStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the application as it is shipped and checks the three things an operator relies
 * on: it serves a real Redis client, it says so through actuator, and it counts what it
 * is doing.
 *
 * <p>
 * Nothing here overrides the wiring. The properties that differ from the defaults are the
 * ephemeral port every test in this module runs on, the health endpoint's detail level,
 * which is off by default because health details are not for everyone, and the exclusion
 * of the Redis client auto-configuration — the end-to-end tests put a Redis client on
 * this module's test classpath, and letting it configure itself would add a health
 * indicator, reporting on a Redis that this application is not a client of, that the
 * shipped application does not have.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"management.endpoint.health.show-details=always",
		"spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration" })
@AutoConfigureRestTestClient
class InMemoryRedisAdapterServerApplicationTests {

	@Autowired
	private KeyValueStores databases;

	@Autowired
	private RedisAdapterServer server;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private RestTestClient client;

	@Test
	void theInMemoryBackendIsWhatItServesFrom() {
		assertThat(this.databases.databases()).hasSize(1);
		KeyValueStore store = this.databases.database(0);
		assertThat(store).isInstanceOf(InMemoryKeyValueStore.class);

		byte[] key = "wiring-probe".getBytes(UTF_8);
		store.append(key, "v".getBytes(UTF_8));
		assertThat(store.exists(key)).isTrue();
	}

	@Test
	void theServerAcceptsARealRedisClient() {
		String reply = connected(connection -> connection.sync().ping());

		assertThat(reply).isEqualTo("PONG");
	}

	@Test
	void healthReportsTheServerIsUpAndOnWhichPort() {
		this.client.get()
			.uri("/actuator/health")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.status")
			.isEqualTo("UP")
			.jsonPath("$.components.redisAdapterServer.status")
			.isEqualTo("UP")
			.jsonPath("$.components.redisAdapterServer.details.port")
			.isEqualTo(this.server.port())
			.jsonPath("$.components.redisAdapterServer.details.databases")
			.isEqualTo(1);
	}

	/**
	 * The gauges are read while a client is connected, since a count of zero would say
	 * nothing about whether the meters are wired to the server at all.
	 */
	@Test
	void connectionMetricsFollowTheServer() {
		List<Double> counts = connected(
				connection -> List.of(this.meterRegistry.get("redis.adapter.connections.active").gauge().value(),
						this.meterRegistry.get("redis.adapter.connections.accepted").functionCounter().count()));

		assertThat(counts.get(0)).as("connections being served").isGreaterThanOrEqualTo(1);
		assertThat(counts.get(1)).as("connections accepted").isGreaterThanOrEqualTo(1);
	}

	/**
	 * Runs an action on a live connection to the adapter, over the same client an
	 * application would use.
	 * @param action what to do with the connection
	 * @return whatever the action returned
	 */
	private <T> T connected(Function<StatefulRedisConnection<String, String>, T> action) {
		RedisClient client = RedisClient.create();
		try (StatefulRedisConnection<String, String> connection = client.connect(StringCodec.UTF8,
				RedisURI.builder()
					.withHost("127.0.0.1")
					.withPort(this.server.port())
					.withTimeout(Duration.ofSeconds(10))
					.build())) {
			return action.apply(connection);
		}
		finally {
			client.shutdown(Duration.ZERO, Duration.ofSeconds(10));
		}
	}

}
