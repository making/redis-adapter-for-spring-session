package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether the adapter is accepting connections, which is the whole of what an
 * application needs from it.
 *
 * <p>
 * A server that is bound but not accepting — during startup, or after a shutdown has
 * begun — is down as far as a client is concerned, and reporting it as such is what lets
 * a load balancer take the instance out before its connections are dropped.
 *
 * <p>
 * Whether the <em>backend</em> can be reached is not asked here. The
 * {@link am.ik.redis.adapter.store.KeyValueStore} SPI has no health in it, and inventing
 * one out of a read would mean guessing what a future backend considers reachable. A
 * backend that can be unreachable contributes a health indicator of its own, and the
 * actuator reports both.
 */
public final class RedisAdapterServerHealthIndicator implements HealthIndicator {

	private final RedisAdapterServer server;

	private final KeyValueStores databases;

	/**
	 * Creates the indicator of a server.
	 * @param server the server to report on
	 * @param databases the backends it serves
	 */
	public RedisAdapterServerHealthIndicator(RedisAdapterServer server, KeyValueStores databases) {
		this.server = server;
		this.databases = databases;
	}

	@Override
	public Health health() {
		if (!this.server.isRunning()) {
			return Health.down().withDetail("reason", "the server is not accepting connections").build();
		}
		return Health.up()
			.withDetail("port", this.server.port())
			.withDetail("databases", this.databases.databases().size())
			.withDetail("connections", this.server.activeConnections())
			.build();
	}

}
