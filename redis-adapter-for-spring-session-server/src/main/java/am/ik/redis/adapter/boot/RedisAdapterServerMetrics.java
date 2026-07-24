package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes what the server is doing to whatever metrics backend the application is
 * configured with.
 *
 * <p>
 * Connections are the measure that matters for a server whose model is one virtual thread
 * per connection: how many are being served says how much of the machine is in use, and
 * how many have been accepted says how fast clients are cycling — a client that
 * reconnects on every request shows up here and nowhere else.
 */
public final class RedisAdapterServerMetrics implements MeterBinder {

	private final RedisAdapterServer server;

	/**
	 * Creates the metrics of a server.
	 * @param server the server to measure
	 */
	public RedisAdapterServerMetrics(RedisAdapterServer server) {
		this.server = server;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("redis.adapter.connections.active", this.server, RedisAdapterServer::activeConnections)
			.description("Client connections the adapter is serving right now")
			.baseUnit("connections")
			.register(registry);
		FunctionCounter.builder("redis.adapter.connections.accepted", this.server, RedisAdapterServer::totalConnections)
			.description("Client connections the adapter has accepted")
			.baseUnit("connections")
			.register(registry);
	}

}
