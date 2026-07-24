package am.ik.redis.adapter.boot;

import java.util.List;

import am.ik.redis.adapter.inmemory.InMemoryKeyValueStore;
import am.ik.redis.adapter.server.RedisAdapterServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health of the adapter is whether it is accepting connections. A server that is
 * bound but not yet accepting, or one that has been stopped, is as useless to an
 * application as one that never started, so both report down.
 */
class RedisAdapterServerHealthIndicatorTests {

	private final KeyValueStores databases = new KeyValueStores(List.of(InMemoryKeyValueStore.create()));

	private final RedisAdapterServer server = RedisAdapterServer.builder()
		.host("127.0.0.1")
		.port(0)
		.databases(this.databases.databases())
		.build();

	private final RedisAdapterServerHealthIndicator indicator = new RedisAdapterServerHealthIndicator(this.server,
			this.databases);

	@AfterEach
	void stopServer() {
		this.server.stop();
		this.databases.close();
	}

	@Test
	void isUpWhileTheServerIsAccepting() {
		this.server.start();

		Health health = this.indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("port", this.server.port())
			.containsEntry("databases", 1)
			.containsEntry("connections", 0);
	}

	@Test
	void isDownWhileTheServerIsOnlyBound() {
		this.server.bind();

		Health health = this.indicator.health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("reason", "the server is not accepting connections");
	}

	@Test
	void isDownOnceTheServerHasStopped() {
		this.server.start();
		this.server.stop();

		assertThat(this.indicator.health().getStatus()).isEqualTo(Status.DOWN);
	}

}
