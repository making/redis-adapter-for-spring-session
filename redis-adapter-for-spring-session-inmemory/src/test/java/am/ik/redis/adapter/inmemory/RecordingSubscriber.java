package am.ik.redis.adapter.inmemory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import am.ik.redis.adapter.pubsub.Subscriber;

/**
 * A {@link Subscriber} that records what it was delivered. The active-expiry sweeper runs
 * on its own thread, so deliveries are queued and waited for rather than read back
 * directly.
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

	private static String text(byte[] value) {
		return new String(value, StandardCharsets.UTF_8);
	}

}
