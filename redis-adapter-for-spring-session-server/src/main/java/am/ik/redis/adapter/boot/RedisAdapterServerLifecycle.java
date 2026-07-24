package am.ik.redis.adapter.boot;

import am.ik.redis.adapter.server.RedisAdapterServer;

import org.springframework.context.SmartLifecycle;

/**
 * Ties the adapter server to the application context: it starts accepting connections
 * once everything else is up, and stops before anything else is taken down.
 *
 * <p>
 * The port is bound earlier than this, as the server bean is created, so that a failure
 * to bind stops the application at once and so that whatever needs the port — an
 * ephemeral one most of all — can read it. Accepting is what waits, because a client
 * served during startup could reach a backend that is not ready.
 *
 * <p>
 * The adapter is meant to run as a process of its own, which is why waiting costs
 * nothing: the applications it serves are started separately and connect whenever they
 * like. An application that instead runs the adapter inside itself must not connect to it
 * while its own context is still starting — the connection would be waiting for a context
 * that is waiting for the connection. Such an application should start the server itself,
 * as this project's end-to-end tests do.
 */
public final class RedisAdapterServerLifecycle implements SmartLifecycle {

	private final RedisAdapterServer server;

	/**
	 * Creates the lifecycle of a server.
	 * @param server the bound but not yet accepting server
	 */
	public RedisAdapterServerLifecycle(RedisAdapterServer server) {
		this.server = server;
	}

	@Override
	public void start() {
		this.server.start();
	}

	@Override
	public void stop() {
		this.server.stop();
	}

	@Override
	public boolean isRunning() {
		return this.server.isRunning();
	}

	/**
	 * Runs in the last phase, so that the adapter starts serving after every other
	 * lifecycle bean — a backend that has to connect somewhere, say — and gives up its
	 * clients before any of them stops.
	 * @return {@link SmartLifecycle#DEFAULT_PHASE}
	 */
	@Override
	public int getPhase() {
		return SmartLifecycle.DEFAULT_PHASE;
	}

}
