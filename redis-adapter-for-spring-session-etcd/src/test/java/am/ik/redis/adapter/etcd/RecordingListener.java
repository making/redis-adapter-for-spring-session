package am.ik.redis.adapter.etcd;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import am.ik.redis.adapter.store.KeyEventListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@link KeyEventListener} that records what it was told, and waits for it.
 *
 * <p>
 * This backend's events arrive over a watch on etcd rather than inside the call that
 * removed the key — which is the whole point, since that is what carries them between
 * replicas — so a test waits for an event instead of reading one back. The wait is
 * generous: etcd expires a key by revoking its lease, and leases are whole seconds.
 */
final class RecordingListener implements KeyEventListener {

	/**
	 * How long to wait for an event. Lease-driven expiry is seconds, not milliseconds.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

	@Override
	public void onExpired(byte[] key) {
		this.events.add("expired " + text(key));
	}

	@Override
	public void onDeleted(byte[] key) {
		this.events.add("deleted " + text(key));
	}

	/**
	 * Waits for the next event.
	 * @return the event, as {@code "<expired|deleted> <key>"}
	 */
	String await() {
		try {
			String event = this.events.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(event).as("an event within %s", TIMEOUT).isNotNull();
			return event;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for a key event", e);
		}
	}

	/**
	 * Waits until the named event has been recorded, ignoring anything else that arrives
	 * first. The keyspace of a running Spring Session is busy, so a test that cares about
	 * one key cannot assume it is told about that key first.
	 * @param expected the event, as {@code "<expired|deleted> <key>"}
	 */
	void awaitEvent(String expected) {
		List<String> seen = new ArrayList<>();
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			try {
				String event = this.events.poll(100, TimeUnit.MILLISECONDS);
				if (event == null) {
					continue;
				}
				seen.add(event);
				if (event.equals(expected)) {
					return;
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for " + expected, e);
			}
		}
		assertThat(seen).as("the events seen while waiting %s for '%s'", TIMEOUT, expected).contains(expected);
	}

	/**
	 * Asserts that nothing is reported for a while, which is how "this operation
	 * announces nothing" is checked.
	 * @param quiet how long to listen
	 */
	void assertSilence(Duration quiet) {
		try {
			String event = this.events.poll(quiet.toMillis(), TimeUnit.MILLISECONDS);
			assertThat(event).as("no key event within %s", quiet).isNull();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while listening for silence", e);
		}
	}

	private static String text(byte[] key) {
		return new String(key, StandardCharsets.UTF_8);
	}

}
