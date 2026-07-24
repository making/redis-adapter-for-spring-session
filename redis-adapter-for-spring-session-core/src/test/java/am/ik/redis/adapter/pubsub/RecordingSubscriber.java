package am.ik.redis.adapter.pubsub;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * A {@link Subscriber} that records what it was delivered, so tests can assert on the
 * routing decisions the registry made without a socket.
 *
 * <p>
 * Deliveries are rendered as text ({@code message <channel> <body>} /
 * {@code pmessage <pattern> <channel> <body>}) because everything the pub/sub layer
 * carries in these tests is UTF-8, and one string per delivery keeps the assertions
 * readable. They are queued rather than merely collected so that a test can wait for a
 * delivery made from another thread, such as an active-expiry sweep.
 */
final class RecordingSubscriber implements Subscriber {

	private final BlockingQueue<String> deliveries = new LinkedBlockingQueue<>();

	@Override
	public void message(byte[] channel, byte[] body) {
		this.deliveries.add("message " + text(channel) + " " + text(body));
	}

	@Override
	public void patternMessage(byte[] pattern, byte[] channel, byte[] body) {
		this.deliveries.add("pmessage " + text(pattern) + " " + text(channel) + " " + text(body));
	}

	/**
	 * Waits for the next delivery.
	 * @return the delivery, or {@code "<none>"} if none arrived within ten seconds
	 * @throws InterruptedException if the waiting thread is interrupted
	 */
	String take() throws InterruptedException {
		String delivery = this.deliveries.poll(10, TimeUnit.SECONDS);
		return (delivery == null) ? "<none>" : delivery;
	}

	/**
	 * Returns the delivery that already arrived, without waiting.
	 * @return the delivery, or {@code "<none>"} if nothing has been delivered
	 */
	String poll() {
		@Nullable String delivery = this.deliveries.poll();
		return (delivery == null) ? "<none>" : delivery;
	}

	private static String text(byte[] value) {
		return new String(value, StandardCharsets.UTF_8);
	}

}
